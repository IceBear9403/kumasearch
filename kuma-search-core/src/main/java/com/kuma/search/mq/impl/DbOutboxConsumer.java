package com.kuma.search.mq.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.constant.OutboxOpType;
import com.kuma.search.constant.OutboxStatus;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventConsumer;
import com.kuma.search.repository.entity.SearchOutbox;
import com.kuma.search.repository.mapper.SearchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 Outbox 表拉取待投递事件的消费者（默认实现）。
 *
 * <h3>「至少一次」是本实现的明确语义</h3>
 * 处理成功与状态更新不是原子的：若在 {@link #ack} 之前进程被杀，事件会被重新投递。
 * 这不是缺陷，而是有意的取舍 —— 换成"先标记再处理"会变成"至多一次"，
 * 那意味着崩溃时事件永久丢失、ES 里少一条文档，且没有任何机制会发现。
 * 两害相权，重复投递可以靠幂等消化（ES 的 {@code _id} 是业务主键，重复写即覆盖），
 * 丢失则只能靠全量对账发现。
 *
 * <h3>为什么按 id 升序拉取</h3>
 * Outbox 的自增 id 反映事件的<b>实际发生顺序</b>。同一条内容被连续修改两次时，
 * 只有按这个顺序消费，ES 里才会留下最后一次修改的内容。
 * 如果并行消费且不保序，就可能在索引里留下中间的旧版本 ——
 * 表现是"改完内容搜出来还是旧的"，且刷新一次又对了，极其难以复现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kuma.mq.type", havingValue = "db-outbox", matchIfMissing = true)
public class DbOutboxConsumer implements IndexEventConsumer {

    private final SearchOutboxMapper searchOutboxMapper;
    private final KumaSearchProperties properties;

    /**
     * 拉取一批待处理事件。
     *
     * <p>只拾取 PENDING 与 FAILED：DEAD 状态是"已放弃自动重试"的终态，
     * 继续自动重投会让一个必然失败的事件无限占用扫描配额，把后面正常的事件饿死。
     * 死信的处理必须由人介入 —— 这正是把它单独作为一个状态的意义。
     */
    @Override
    public List<IndexEvent> poll(int batchSize) {
        LocalDateTime now = LocalDateTime.now();

        List<SearchOutbox> rows = searchOutboxMapper.selectList(
                new LambdaQueryWrapper<SearchOutbox>()
                        .in(SearchOutbox::getStatus,
                                OutboxStatus.PENDING.getCode(), OutboxStatus.FAILED.getCode())
                        // next_retry_time 为空表示"新产生、尚未尝试过"，应当立即可投递；
                        // 只有重试失败的行才有值。这个区别是重试退避能生效的关键。
                        .and(w -> w.isNull(SearchOutbox::getNextRetryTime)
                                .or().le(SearchOutbox::getNextRetryTime, now))
                        .orderByAsc(SearchOutbox::getId)
                        .last("LIMIT " + batchSize));

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<IndexEvent> events = new ArrayList<>(rows.size());
        for (SearchOutbox row : rows) {
            events.add(toEvent(row));
        }
        return events;
    }

    /**
     * 积压量。用 {@code COUNT(*)} 而不是把行拉出来数 —— 这个方法会被健康检查反复调用，
     * 拉数据意味着每次都要反序列化上千条含整篇正文的 payload。
     */
    @Override
    public long backlogSize() {
        try {
            Long count = searchOutboxMapper.selectCount(
                    new LambdaQueryWrapper<SearchOutbox>()
                            .in(SearchOutbox::getStatus,
                                    OutboxStatus.PENDING.getCode(), OutboxStatus.FAILED.getCode()));
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("统计 Outbox 积压量失败，原因={}", e.getMessage());
            // 返回 -1 而不是 0：把"查不到"伪装成"没有积压"会让告警彻底失效。
            return -1L;
        }
    }

    @Override
    public void ack(IndexEvent event) {
        if (event == null) {
            return;
        }
        searchOutboxMapper.update(null, new LambdaUpdateWrapper<SearchOutbox>()
                .eq(SearchOutbox::getEventId, event.getEventId())
                .set(SearchOutbox::getStatus, OutboxStatus.SUCCESS.getCode())
                .set(SearchOutbox::getErrorMsg, null));
    }

    /**
     * 标记失败并按指数退避安排下次重试。
     *
     * <p>退避是必须的：ES 不可用这类故障通常需要几十秒到几分钟恢复，
     * 固定间隔重试会让失败的事件以全速反复冲击一个已经出问题的集群，
     * 反而拖长恢复时间。
     *
     * <p>超过 {@code kuma.sync.max-retry} 次后转入 DEAD，停止自动重试。
     * 一个永远失败的事件（例如 payload 里的字段与 mapping 冲突）如果不转入死信，
     * 会长期霸占每一轮的扫描配额。
     */
    @Override
    public void nack(IndexEvent event, String error) {
        if (event == null) {
            return;
        }
        String message = DbOutboxPublisher.truncateError(error);

        // 只在失败路径上多读一次数据库。失败是低频事件，这次额外查询的代价可以忽略；
        // 换来的是消费端完全无状态，不需要在内存里维护 eventId → retryCount 的映射，
        // 也就不存在"进程重启后重试次数丢失、退避失效"的问题。
        SearchOutbox row = searchOutboxMapper.selectOne(new LambdaQueryWrapper<SearchOutbox>()
                .eq(SearchOutbox::getEventId, event.getEventId())
                .last("LIMIT 1"));

        if (row == null) {
            log.warn("nack 时找不到对应事件，已忽略，eventId={}", event.getEventId());
            return;
        }

        int retryCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        int maxRetry = properties.getSync().getMaxRetry();

        LambdaUpdateWrapper<SearchOutbox> update = new LambdaUpdateWrapper<SearchOutbox>()
                .eq(SearchOutbox::getId, row.getId())
                .set(SearchOutbox::getRetryCount, retryCount)
                .set(SearchOutbox::getErrorMsg, message);

        if (retryCount >= maxRetry) {
            update.set(SearchOutbox::getStatus, OutboxStatus.DEAD.getCode())
                    .set(SearchOutbox::getNextRetryTime, null);
            log.error("索引事件重试 {} 次仍失败，已转入死信，eventId={}，type={}，bizId={}，原因={}",
                    retryCount, event.getEventId(), event.getTypeCode(), event.getBizId(), message);
        } else {
            long backoffSeconds = computeBackoffSeconds(retryCount);
            update.set(SearchOutbox::getStatus, OutboxStatus.FAILED.getCode())
                    .set(SearchOutbox::getNextRetryTime, LocalDateTime.now().plusSeconds(backoffSeconds));
            log.warn("索引事件投递失败，第 {} 次重试将在 {} 秒后进行，eventId={}，原因={}",
                    retryCount, backoffSeconds, event.getEventId(), message);
        }

        searchOutboxMapper.update(null, update);
    }

    /**
     * 指数退避：{@code base * 2^(retryCount - 1)}。
     *
     * <p>上限封顶 10 分钟。不封顶的话，第 10 次重试要等 85 分钟，
     * 那时问题早就被人工处理完了，这条事件却还卡在那里 —— 反而制造了新的不一致。
     */
    private long computeBackoffSeconds(int retryCount) {
        long base = Math.max(1, properties.getSync().getRetryBackoffSeconds());
        long delay = base * (1L << Math.min(retryCount - 1, 16));
        return Math.min(delay, 600);
    }

    /**
     * Outbox 行 → 事件对象。
     *
     * <p>{@code opType} 在库里存的是枚举名（字符串）而不是序号：
     * 存序号的话，将来在枚举中间插入一个新值就会让历史数据的含义整体错位。
     */
    private IndexEvent toEvent(SearchOutbox row) {
        OutboxOpType opType;
        try {
            opType = row.getOpType() == null
                    ? OutboxOpType.UPSERT
                    : OutboxOpType.valueOf(row.getOpType());
        } catch (IllegalArgumentException e) {
            // 库里有无法识别的操作类型时按 UPSERT 处理并告警：
            // 写入一个多余的文档比漏掉一次删除更容易被发现。
            log.warn("无法识别的 OutboxOpType={}，按 UPSERT 处理，eventId={}",
                    row.getOpType(), row.getEventId());
            opType = OutboxOpType.UPSERT;
        }

        return IndexEvent.builder()
                .eventId(row.getEventId())
                .typeCode(row.getTypeCode())
                .bizId(row.getBizId())
                .opType(opType)
                .payload(row.getPayload())
                .occurredAt(row.getCreateTime())
                .build();
    }
}
