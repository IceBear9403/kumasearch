package com.kuma.search.mq.impl;

import com.kuma.search.constant.OutboxOpType;
import com.kuma.search.constant.OutboxStatus;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventPublisher;
import com.kuma.search.repository.entity.SearchOutbox;
import com.kuma.search.repository.mapper.SearchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 基于数据库 Outbox 表的事件发布实现（默认实现）。
 *
 * <h3>为什么是本项目的默认选择</h3>
 * 它能在业务写入的<b>同一个本地事务</b>里完成事件记录，这一点是消息中间件做不到的：
 * 用 RocketMQ 时，「写 MySQL」与「发 MQ」是两个系统，两者之间必然存在一个失败窗口，
 * 只能靠本地消息表 + 对账来补偿 —— 那本质上就是把 Outbox 又实现了一遍，只是多了一个中间件。
 * 在 10 万文档 / 500 QPS 的目标规模下，直接用 Outbox 更简单、更少组件、语义更强。
 *
 * <h3>本类绝对不能做的事</h3>
 * <ul>
 *   <li><b>不要加 {@code @Transactional(REQUIRES_NEW)}</b>。开了新事务就等于放弃了
 *       「与业务写入同生共死」这个唯一的优势 —— 业务回滚了事件却留下来了，
 *       消费者会去 ES 里写入一条数据库中并不存在的文档。</li>
 *   <li><b>不要在这里发 HTTP / RPC</b>。调用方处于事务中，任何远程调用都会把事务
 *       的持锁时间拉长到网络往返的量级，在高并发下直接演变成锁等待。</li>
 * </ul>
 *
 * <h3>什么时候该换成真正的 MQ</h3>
 * 当单表 Outbox 成为瓶颈时：即扫描 + 删除的速度跟不上写入速度，
 * 表持续增长导致扫描越来越慢。这个拐点通常在每秒数千次写入以上，
 * 远超本项目的当前目标。到那时只需要把 {@code kuma.mq.type} 改成 {@code rocketmq}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kuma.mq.type", havingValue = "db-outbox", matchIfMissing = true)
public class DbOutboxPublisher implements IndexEventPublisher {

    private final SearchOutboxMapper searchOutboxMapper;

    /** error_msg 字段只保留摘要，避免超长堆栈把行撑爆。 */
    private static final int MAX_ERROR_MSG_LENGTH = 500;

    @Override
    public void publish(IndexEvent event) {
        if (event == null) {
            return;
        }
        searchOutboxMapper.insert(toEntity(event));
    }

    /**
     * 批量发布。
     *
     * <p>逐条 {@code insert} 在批量导入场景下会产生 N 次数据库往返。
     * 这里仍然逐条插入而没有用 MyBatis-Plus 的批量 API，是因为该 API 依赖
     * JDBC 的 rewriteBatchedStatements 与连接参数配合，收益不稳定且行为随驱动版本变化；
     * 在有明确压测数据之前，保持实现简单更划算。真正成为瓶颈时，
     * 换 MQ 实现比分批插入更值得做。
     */
    @Override
    public void publishBatch(List<IndexEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (IndexEvent event : events) {
            searchOutboxMapper.insert(toEntity(event));
        }
        log.debug("批量投递索引事件完成，数量={}", events.size());
    }

    /**
     * 事件 → Outbox 行。
     *
     * <p>初始状态设为 PENDING 而不是"立刻可投递"：扫描条件里看的是
     * {@code status = PENDING AND next_retry_time <= now}，
     * 新插入的行 {@code next_retry_time} 为空，由消费端按"空即立刻可投递"处理。
     */
    private SearchOutbox toEntity(IndexEvent event) {
        return SearchOutbox.builder()
                .eventId(event.getEventId())
                .typeCode(event.getTypeCode())
                .bizId(event.getBizId())
                .opType(event.getOpType() == null
                        ? OutboxOpType.UPSERT.name()
                        : event.getOpType().name())
                .payload(event.getPayload())
                .status(OutboxStatus.PENDING.getCode())
                .retryCount(0)
                .build();
    }

    /** 供消费端复用：把超长的错误信息截断。 */
    static String truncateError(String error) {
        if (!StringUtils.hasText(error)) {
            return null;
        }
        return error.length() <= MAX_ERROR_MSG_LENGTH
                ? error
                : error.substring(0, MAX_ERROR_MSG_LENGTH) + "...";
    }
}
