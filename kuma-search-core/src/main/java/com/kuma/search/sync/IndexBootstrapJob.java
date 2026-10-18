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
 * 自动建索引：定期检查每个启用中的类型有没有索引，缺了就建，源表有数据就顺手灌进去。
 *
 * <h3>为什么需要它，而不是靠一次性的部署步骤</h3>
 * 「新环境起来后要先建索引」这件事，现状是靠人去点管理后台的按钮或敲 curl
 * （见 {@code docs/09-maintenance.md} §4.3）。这个约定的问题是<b>它没有失败信号</b>：
 * 忘了建索引的表现是"搜索返回空结果"（索引不存在时检索降级为空结果，见
 * {@code EsSearchGateway#emptyResult}），而不是一条报错。于是它会被读成"还没收录内容"，
 * 排查方向从一开始就错了 —— 这正是那个「降级成空结果」的取舍带来的副作用，
 * 本类是它的对症补丁。
 *
 * <h3>它做两件事，第二件不能省</h3>
 * <ol>
 *   <li>{@link DynamicIndexManager#createIndex} —— 建物理索引 + 绑别名 + 建联想索引；</li>
 *   <li>若源表<b>已有</b>已发布数据，紧接着 {@link DynamicIndexManager#rebuildIndex} 把数据灌进去。</li>
 * </ol>
 * 只做第 1 步会得到一个<b>空索引</b>，而空索引与"真的没有内容"在检索侧完全一样：
 * 都是零条命中、都是 {@code partial=false}、都不报错。灌数据这一步没有别的替代品 ——
 * Outbox 只覆盖"走本系统接口的写入"，增量同步只扫"被改动过的行"，两者都不会去补存量数据
 * （见 {@code IncrementalSyncJob} 的类注释）。
 *
 * <h3>判断条件是「索引集合完整」，不是「主索引存在」</h3>
 * 一个类型需要的索引不止一个：主索引 {@code kuma_{typeCode}_v{n}} 之外，还有联想索引
 * {@code kuma_{typeCode}_suggest}（是否必需由配置里有没有 {@code suggest=true} 的字段决定）。
 * 两者是分两步建的，中间任何一步失败都会留下"主索引在、联想索引不在"的半成品状态。
 * 只判断主索引的话，这种状态<b>永远不会被修</b>：本任务每轮都会认为"已经有了"而返回，
 * 用户侧只表现为每次输入都刷一条 {@code no such index} 的 WARN。
 * 因此 {@link #bootstrapOne} 对已存在的类型还会再确认一次集合完整性，
 * 缺哪一项补哪一项。
 *
 * <h3>边界：它只补"缺失"，从不"覆盖"</h3>
 * 判断条件是"该在的不在" —— <b>已经存在的索引一律不碰</b>。
 * 因此它不可能与人工重建冲突，也不可能覆盖线上正在服务的索引。
 * 一个类型建好之后，本任务此后每轮对它都只是多跑两次轻量的 exists 检查。
 *
 * <p>代价说清楚：{@code rebuildIndex} 是同步的重操作（建新版本索引 → 全量灌数据 → 校验 →
 * 切别名 → 删旧索引），大数据量时单次可达几十秒到几分钟。它跑在调度线程上，
 * 期间这一轮不结束、下一轮不开始（{@code fixedDelay}），不会堆积；
 * 并且它与人工重建共用同一把分布式锁（{@code rebuildIndex} 内部自己加），
 * 两边撞上时后到的一方会直接失败而不是并行跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexBootstrapJob {

    /** 与 {@code CacheKeys.syncTaskLock} 共用前缀。名字里带 index 是为了在 Redis 里一眼能认出来。 */
    private static final String LOCK_NAME = "index-auto-create";

    /**
     * 每轮最长持有的锁时间。
     *
     * <p>取值必须明显大于"一轮最坏耗时"，否则锁会在任务还在跑的时候过期，
     * 下一个实例/下一轮就会并发进来，两边同时跑重建。10 分钟是三倍于最慢一次重建的估计，
     * 宁可锁久了白等一轮（下一轮 fixedDelay 而已），也不要锁短了并发。
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final IndexTypeConfigService indexTypeConfigService;
    private final DynamicIndexManager dynamicIndexManager;
    private final DistributedLock distributedLock;
    private final KumaSearchProperties properties;

    /**
     * 自动建索引调度。默认 10 分钟一轮。
     *
     * <p>周期选得比其它任务（秒级）长得多，因为"缺索引"是需要人工介入才能产生的状态
     * （要么是新环境首次启动，要么是有人手工删了索引），不是持续变化的数据。
     * 每秒查一次的收益只是"建得更快 10 分钟"，代价是每分钟对每个类型发一次 exists 请求。
     *
     * <p>启动时的第一批尤其重要：应用刚起来时索引往往还不存在，
     * 而 {@code fixedDelay} 的第一次执行在启动后立即发生（无需等一个周期），
     * 所以新环境起来后大约几秒内索引就会被建好，不需要任何人去点按钮。
     */
    @Scheduled(fixedDelayString = "${kuma.sync.auto-create-interval-millis:600000}")
    public void run() {
        if (!properties.getSync().isAutoCreateEnabled()) {
            return;
        }

        String token = distributedLock.tryLock(CacheKeys.syncTaskLock(LOCK_NAME), LOCK_TTL);
        if (token == null) {
            // 别的实例正在跑（或上一轮还没结束）。不记 WARN：这在多实例部署下是常态，
            // 每分钟一条 WARN 会让真正的告警被淹没。
            log.debug("自动建索引：未取得锁，跳过本轮");
            return;
        }

        try {
            bootstrapMissingIndexes();
        } finally {
            distributedLock.unlock(CacheKeys.syncTaskLock(LOCK_NAME), token);
        }
    }

    /**
     * 逐个检查启用中的类型。
     *
     * <p>刻意<b>不做</b> {@code @Transactional}：这里会发起 ES 调用与可能长达数分钟的灌数据，
     * 把它们圈进一个事务等于长时间占用数据库连接（而且 ES 操作本来就不受事务保护，
     * 回滚也回滚不了已经建好的索引 —— 那只会给人一个"已经回滚了"的错觉）。
     *
     * <p>单个类型失败不影响其余类型：一个类型的 {@code field_config} 配置有误
     * （比如主键字段没声明）不该让别的类型也建不了索引。
     */
    private void bootstrapMissingIndexes() {
        List<IndexTypeConfig> configs = indexTypeConfigService.listAll();
        for (IndexTypeConfig config : configs) {
            // listAll 返回的是"全部（含停用）"。停用的类型必须跳过：
            // resolveDefinition 对停用类型会抛异常，而停用是管理员明确表达的"别用它"，
            // 顺手把它启用（建索引）既违背意图，也会让"停用即时生效"这条承诺失效。
            if (config.getStatus() == null || config.getStatus() != 1) {
                continue;
            }
            String typeCode = config.getTypeCode();
            try {
                bootstrapOne(typeCode);
            } catch (Exception e) {
                // 不让一个类型的失败中断整轮。这里记 ERROR 而不是 WARN：
                // 走到这里意味着某个类型<b>本轮没有索引可用</b>，属于需要人看一眼的状态。
                log.error("自动建索引失败，已跳过该类型，type={}，原因={}", typeCode, e.getMessage(), e);
            }
        }
    }

    private void bootstrapOne(String typeCode) {
        if (!dynamicIndexManager.exists(typeCode)) {
            log.info("类型 {} 没有索引，开始自动创建", typeCode);
            dynamicIndexManager.createIndex(typeCode);

            long sourceRows = dynamicIndexManager.countSourceRows(typeCode);
            if (sourceRows <= 0) {
                // 源表本来就是空的。此时灌数据没有意义（重建一次也还是零条），
                // 而且会让本该"秒级完成"的首轮变成一次全量重建。等有数据进来时，
                // Outbox / 增量同步会把它们写进去 —— 那条路不需要索引预先存在。
                log.info("类型 {} 的索引已创建；源表暂无已发布数据，跳过灌数据", typeCode);
                return;
            }

            log.info("类型 {} 的索引已创建，源表有 {} 条已发布数据，继续全量灌数据", typeCode, sourceRows);
            dynamicIndexManager.rebuildIndex(typeCode);
            log.info("类型 {} 的自动建索引 + 灌数据完成", typeCode);
            return;
        }

        // 【主索引在 ≠ 这个类型的索引集合是完整的】
        // 这是本类最容易漏掉的一半判断。联想索引是独立索引，它与主索引分两次创建，
        // 中间任何一步失败（历史真实案例：联想索引的建索引请求被 ES 以命名冲突拒掉）
        // 都会留下"主索引在、联想索引不在"的状态。而在此之前，本类只判断主索引，
        // 于是它每轮都认为"已经有了"直接返回 —— 一次性的失败被固化成永久状态，
        // 用户侧的表现是每一次输入都刷一条 no such index 的 WARN，且永远不会自愈。
        // 现在把判断条件从"主索引存在"改成"索引集合完整"，这个状态就成了可修复的。
        if (dynamicIndexManager.ensureSuggestIndex(typeCode)) {
            log.warn("类型 {} 的主索引存在但联想索引缺失，已补建。这个状态说明此前有一次建索引只完成了一半"
                            + "（见 docs/08-roadmap.md 第 21 项），ES 侧请核对 _cat/indices 确认索引集合已齐",
                    typeCode);
        }
    }
}
