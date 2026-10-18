package com.kuma.search.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.cache.CacheKeys;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.constant.OutboxOpType;
import com.kuma.search.es.gateway.EsIndexGateway;
import com.kuma.search.es.gateway.EsSearchGateway;
import com.kuma.search.es.mapping.DocumentConverter;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventPublisher;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.SearchContentMapper;
import com.kuma.search.service.IndexTypeConfigService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 对账任务：发现 MySQL 与 ES 之间的数据漂移。
 *
 * <h3>为什么必须有它，即使已经有了 Outbox 和增量同步</h3>
 * 前两者都是"尽力而为"的补偿手段，各自都有覆盖不到的角落：
 * <ul>
 *   <li>Outbox 覆盖不到旁路写入；</li>
 *   <li>增量同步覆盖不到<b>删除</b> —— 逻辑删除后的行不会出现在
 *       {@code status = 1} 的扫描结果里，于是 ES 里那条文档永远不会被清掉，
 *       表现为"删掉的内容还能搜到，点进去 404"。</li>
 * </ul>
 * 对账是最后一道防线：它不假设任何机制正常工作，只比对两边的实际状态。
 * 这也是为什么它的实现要尽量简单、尽量少依赖其他组件 ——
 * 一个复杂的对账逻辑本身就是新的故障源。
 *
 * <h3>为什么只抽样而不做全量逐条比对</h3>
 * 10 万文档全量逐条比对需要把 ES 里的 id 全部 scroll 出来（一次几百 MB 的网络传输），
 * 只为发现几条差异，性价比极低。这里采取"计数比对 + 抽样存在性检查"：
 * 计数能发现"大面积缺失"这类严重问题，抽样能发现"个别文档没同步"。
 * 两者都正常时，可以认为数据是一致的 —— 这不是数学证明，但足够用于生产判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private final SearchContentMapper searchContentMapper;
    private final IndexTypeConfigService indexTypeConfigService;
    private final EsIndexGateway esIndexGateway;
    private final EsSearchGateway esSearchGateway;
    private final IndexEventPublisher indexEventPublisher;
    private final DocumentConverter documentConverter;
    private final KumaSearchProperties properties;
    private final DistributedLock distributedLock;

    /** 抽样的条数。取最近的 N 条，因为最新的数据最容易出问题（同步还没跑完、事件还在队列里）。 */
    private static final int SAMPLE_SIZE = 200;

    /** 对账调度。默认每 30 分钟一次，避开业务高峰的整点。 */
    @Scheduled(fixedDelayString = "${kuma.sync.reconcile-interval-millis:1800000}")
    public void run() {
        if (!properties.getSync().isReconcileEnabled()) {
            return;
        }

        String lockKey = CacheKeys.syncTaskLock("reconcile");
        String token = distributedLock.tryLock(lockKey, Duration.ofMinutes(30));
        if (token == null) {
            return;
        }

        try {
            for (String typeCode : listTypeCodes()) {
                try {
                    ReconcileResult result = reconcile(typeCode);
                    if (!result.isConsistent()) {
                        log.error("数据对账发现差异: {}", result);
                    } else {
                        log.info("数据对账通过: {}", result);
                    }
                } catch (Exception e) {
                    log.error("对账失败，type={}", typeCode, e);
                }
            }
        } finally {
            distributedLock.unlock(lockKey, token);
        }
    }

    /**
     * 对账单个类型。
     *
     * <p>这里的差异判断刻意<b>只报告、不自动修复</b>。自动修复看起来更省事，
     * 但一个错误的对账逻辑会自动地把正确的数据改坏 —— 那比数据不一致严重得多。
     * 修复动作由人确认后通过 {@link #repair(String)} 触发。
     *
     * @throws BizException 类型配置不存在时抛出
     */
    public ReconcileResult reconcile(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        String indexName = def.physicalIndexName();

        if (!esIndexGateway.exists(indexName)) {
            return ReconcileResult.builder()
                    .typeCode(typeCode)
                    .indexExists(false)
                    .consistent(false)
                    .message("索引不存在，需要先创建索引并全量重建: " + indexName)
                    .build();
        }

        long dbCount = countSourceRows(typeCode);
        long esCount = esIndexGateway.countDocuments(indexName);

        // ===== 抽样检查 =====
        List<SearchContent> samples = sampleRecent(typeCode, SAMPLE_SIZE);
        List<String> missingIds = new ArrayList<>();
        for (SearchContent row : samples) {
            if (!esSearchGateway.existsById(def, row.getBizId())) {
                missingIds.add(row.getBizId());
            }
        }

        int tolerance = properties.getSync().getReconcileTolerance();
        boolean countOk = Math.abs(esCount - dbCount) <= tolerance;
        // 抽样里出现缺失就是确凿的不一致，不受 tolerance 影响 ——
        // tolerance 是给"索引正在写入中"这类瞬时差异留的余量，不是给已知缺失留的。
        boolean sampleOk = missingIds.isEmpty();

        return ReconcileResult.builder()
                .typeCode(typeCode)
                .indexExists(true)
                .dbCount(dbCount)
                .esCount(esCount)
                .sampledCount(samples.size())
                .missingSampleIds(missingIds)
                .countConsistent(countOk)
                .sampleConsistent(sampleOk)
                .consistent(countOk && sampleOk)
                .message(buildMessage(dbCount, esCount, missingIds))
                .build();
    }

    /**
     * 手工修复：把对账发现缺失的文档重新投递一次。
     *
     * <p>只做"补写"，不做"删除多余的"。原因：ES 里多出来的文档可能是
     * 上一个版本的残留、也可能是刚被别的路径写进去的合法数据，
     * 仅凭对账结果无法区分。误删是破坏性的，误留只是多了几条搜索结果。
     *
     * @return 补投的事件数
     */
    public int repair(String typeCode) {
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);
        List<SearchContent> samples = sampleRecent(typeCode, SAMPLE_SIZE);

        List<IndexEvent> events = new ArrayList<>();
        for (SearchContent row : samples) {
            if (!esSearchGateway.existsById(def, row.getBizId())) {
                events.add(IndexEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .typeCode(typeCode)
                        .bizId(row.getBizId())
                        .opType(OutboxOpType.UPSERT)
                        .payload(documentConverter.toDocumentJson(def, row))
                        .occurredAt(LocalDateTime.now())
                        .build());
            }
        }

        if (!events.isEmpty()) {
            indexEventPublisher.publishBatch(events);
            log.warn("对账修复已投递 {} 条补写事件，type={}", events.size(), typeCode);
        }
        return events.size();
    }

    private List<String> listTypeCodes() {
        return indexTypeConfigService.listAll().stream()
                .filter(c -> Integer.valueOf(1).equals(c.getStatus()))
                .map(c -> c.getTypeCode())
                .toList();
    }

    private long countSourceRows(String typeCode) {
        Long count = searchContentMapper.selectCount(new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, typeCode)
                .eq(SearchContent::getStatus, 1));
        return count == null ? 0L : count;
    }

    /**
     * 取最近更新的 N 条作为抽样。
     *
     * <p>按 {@code update_time} 倒序而不是随机：最新的数据最可能处于
     * "刚改完、事件还在路上"的状态，也就是最可能暴露问题的地方。
     * 随机抽样会把大量陈旧且必然正确的数据混进来，稀释掉信号。
     */
    private List<SearchContent> sampleRecent(String typeCode, int size) {
        List<SearchContent> rows = searchContentMapper.selectList(
                new LambdaQueryWrapper<SearchContent>()
                        .eq(SearchContent::getTypeCode, typeCode)
                        .eq(SearchContent::getStatus, 1)
                        .orderByDesc(SearchContent::getUpdateTime)
                        .orderByDesc(SearchContent::getId)
                        .last("LIMIT " + size));
        return rows == null ? List.of() : rows;
    }

    private String buildMessage(long dbCount, long esCount, List<String> missingIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("MySQL=").append(dbCount).append(", ES=").append(esCount);
        if (dbCount > esCount) {
            sb.append("，ES 少了 ").append(dbCount - esCount).append(" 条");
        } else if (esCount > dbCount) {
            sb.append("，ES 多了 ").append(esCount - dbCount).append(" 条（可能有已删除但未清理的文档）");
        }
        if (!missingIds.isEmpty()) {
            sb.append("，抽样中发现 ").append(missingIds.size()).append(" 条缺失，样例: ")
                    .append(missingIds.subList(0, Math.min(5, missingIds.size())));
        }
        return sb.toString();
    }

    /** 对账结果。 */
    @Data
    @Builder
    public static class ReconcileResult {
        private String typeCode;
        private boolean indexExists;
        private long dbCount;
        private long esCount;
        private int sampledCount;
        private List<String> missingSampleIds;
        private boolean countConsistent;
        private boolean sampleConsistent;
        private boolean consistent;
        private String message;
    }
}
