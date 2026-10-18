package com.kuma.search.sync;

import com.kuma.search.cache.CacheKeys;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.repository.entity.IndexTypeConfig;
import com.kuma.search.service.DynamicIndexManager;
import com.kuma.search.service.IndexTypeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 定时全量灌词：把每个类型的建议词重新灌一遍。
 *
 * <h3>为什么除了"发布时增量"之外还需要它</h3>
 * 增量灌词挂在 {@code IndexEventHandler} 上，只覆盖"走本系统接口产生的写入"。
 * 它<b>永远不会去补存量数据</b>：本功能上线前就已发布的内容、
 * 手工往数据库插的行、以及之前灌词失败的部分，没有任何事件会把它们带进联想索引。
 * 这与主索引需要 {@code IndexBootstrapJob} 去灌存量数据是完全同一个问题。
 *
 * <p>它同时是<b>灌词失败的最终兜底</b>：增量那条路失败只记 WARN（见
 * {@code IndexEventHandler#syncSuggestQuietly}），能把它纠正回来的就是本任务。
 *
 * <h3>为什么是"删了重建"而不是增量补齐</h3>
 * 每次全量都是「删联想索引 → 重建 → 重灌」。这不是浪费，而是必须：
 * 分析器、{@code max_input_length} 这些都在索引的 settings 里，ES 不允许改已有索引的
 * settings，只有重建才能让"灌词规则的变化"生效。详见
 * {@code DynamicIndexManager#rebuildSuggestIndex}。
 *
 * <p>代价是重建期间联想为空（通常几秒到几十秒）。因此这个周期<b>不该</b>
 * 被缩短到分钟级：新增内容的联想可见性由增量那条路保证，不靠缩短全量周期。
 *
 * <h3>与自动建索引任务的关系</h3>
 * 两者都会增删索引结构，因此可能撞上（应用启动时两个任务的首次执行几乎同时发生）。
 * 但两者都是幂等的：一边在删+建、另一边在 upsert 时，最坏结果是日志里几条
 * 写入失败的 WARN，而最终状态一定是对的（后完成的那次全量会灌满整个索引）。
 * 为这点日志噪音引入跨任务的全局互斥不值得 —— 那样会让"灌词"这件事
 * 被另一个任务的运行时长绑架（{@code IndexBootstrapJob} 一轮可能跑几分钟）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestRebuildJob {

    /** 与 {@code CacheKeys.syncTaskLock} 共用前缀，名字里带 suggest 便于在 Redis 里认出来。 */
    private static final String LOCK_NAME = "suggest-rebuild";

    /**
     * 每轮最长持锁时间。
     *
     * <p>比自动建索引任务的 10 分钟更长，因为灌词要做两件慢事：
     * 逐条 {@code _analyze} 取拼音（每条一次往返）、以及批量写入。
     * 上限 5000 条的默认配置下实测在秒级到十几秒，30 分钟留足了余量。
     * 锁过期早于任务结束会导致并发灌词（虽然幂等，但会白烧 CPU 与 ES 请求）。
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final IndexTypeConfigService indexTypeConfigService;
    private final DynamicIndexManager dynamicIndexManager;
    private final DistributedLock distributedLock;
    private final KumaSearchProperties properties;

    /**
     * 定时全量灌词。默认 6 小时一轮。
     *
     * <p>与其它任务一致用 {@code fixedDelay}：任务自己的耗时不计入周期，
     * 因此不会在慢的时候堆积。启动后立即执行第一轮 ——
     * 新环境起来时联想索引往往是空的（存量数据没人灌），这一轮就是它变可用的时刻。
     */
    @Scheduled(fixedDelayString = "${kuma.suggest.rebuild-interval-millis:21600000}")
    public void run() {
        if (!properties.getSuggest().isRebuildEnabled()) {
            return;
        }

        String token = distributedLock.tryLock(CacheKeys.syncTaskLock(LOCK_NAME), LOCK_TTL);
        if (token == null) {
            // 别的实例正在跑。多实例下这是常态，记 DEBUG 而不是 WARN。
            log.debug("全量灌词：未取得锁，跳过本轮");
            return;
        }

        try {
            rebuildAll();
        } finally {
            distributedLock.unlock(CacheKeys.syncTaskLock(LOCK_NAME), token);
        }
    }

    /**
     * 逐个类型重建联想索引。
     *
     * <p>只处理"启用了、且配置里真的有 suggest 字段"的类型：其余类型调
     * {@code rebuildSuggestIndex} 只会得到一条"本就不该有联想索引"的 WARN，
     * 每 6 小时刷一遍这种日志，会把真正的告警淹没。
     *
     * <p>单个类型失败不影响其余类型，这一点与 {@code IndexBootstrapJob} 一致。
     */
    private void rebuildAll() {
        List<IndexTypeConfig> configs = indexTypeConfigService.listAll();
        int rebuilt = 0;
        int failed = 0;

        for (IndexTypeConfig config : configs) {
            if (config.getStatus() == null || config.getStatus() != 1) {
                // 停用的类型不灌词：resolveDefinition 对停用类型会抛异常，
                // 而停用本身就是"别用它"的意思。
                continue;
            }
            String typeCode = config.getTypeCode();
            if (!hasSuggestField(typeCode)) {
                continue;
            }
            try {
                dynamicIndexManager.rebuildSuggestIndex(typeCode);
                rebuilt++;
            } catch (Exception e) {
                failed++;
                log.error("全量灌词失败，已跳过该类型，type={}，原因={}", typeCode, e.getMessage(), e);
            }
        }

        if (rebuilt > 0 || failed > 0) {
            log.info("全量灌词结束，成功={} 个类型，失败={} 个类型", rebuilt, failed);
        } else {
            log.debug("全量灌词：没有任何类型配置了 suggest 字段，无事可做");
        }
    }

    /** 类型是否声明了 suggest 字段。解析失败（配置损坏）按"没有"处理，不让一个坏配置中断整轮。 */
    private boolean hasSuggestField(String typeCode) {
        try {
            return indexTypeConfigService.resolveDefinition(typeCode).hasSuggestField();
        } catch (Exception e) {
            log.warn("类型 {} 的定义解析失败，本轮跳过灌词，原因={}", typeCode, e.getMessage());
            return false;
        }
    }
}
