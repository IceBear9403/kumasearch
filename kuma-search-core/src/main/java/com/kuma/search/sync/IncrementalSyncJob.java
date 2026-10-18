package com.kuma.search.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.cache.CacheKeys;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.constant.OutboxOpType;
import com.kuma.search.constant.SyncTaskType;
import com.kuma.search.es.mapping.DocumentConverter;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventPublisher;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.entity.SyncTask;
import com.kuma.search.repository.mapper.SearchContentMapper;
import com.kuma.search.repository.mapper.SyncTaskMapper;
import com.kuma.search.service.IndexTypeConfigService;
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
 * 增量同步：把 MySQL 中被外部变更过的数据补进索引。
 *
 * <h3>为什么需要它，既然已经有 Outbox 了</h3>
 * Outbox 只覆盖<b>走本系统接口的写入</b>。现实中源数据几乎总会被旁路修改：
 * 运维手工 UPDATE 修数据、别的系统直连同一个库、老代码路径还没接进来。
 * 这些变更不会产生任何事件，索引会永久落后于数据库。
 * 增量扫描是兜底手段 —— 它不依赖"谁改了数据"，只依赖"数据被改过"这个事实。
 *
 * <h3>游标为什么是「时间 + 主键」两列</h3>
 * 只用一个时间游标会漏数据：同一秒内（MySQL 的 DATETIME 默认精确到秒）
 * 可能有 100 条记录被写入，第一次扫描取走前 500 条时若恰好截断在某个时间点中间，
 * 下次用 {@code update_time > 该时间} 扫就会跳过那一刻剩下的记录。
 * 加上自增主键作为第二排序维度后，游标变成「(时间, 主键) 的字典序」，
 * 每一批的边界都精确可续，不重不漏。
 *
 * <p>即便如此还是额外加了 {@code overlapSeconds} 的回溯 —— 数据库的
 * {@code update_time} 由应用填充，在时钟回拨或跨实例时钟不一致时仍可能乱序。
 * 多扫几秒的代价是重复投递一些事件，而重复是幂等的，漏掉则不是。
 *
 * <h3>回溯窗口只生效一次（{@code recheck_pending}）</h3>
 * 上面那句"重复投递可以接受"有一条隐含前提：重复的次数是<b>有界</b>的。原先的实现没有满足它 ——
 * 每个类型的第一个批次无条件回溯，而回溯扫到的行必然都小于等于水位，
 * 于是 {@code cursorTime = last.getUpdateTime()} 只是把水位<b>赋成它已有的值</b>，
 * 水位永远无法前进。结果是一个类型只要安静下来（不再有新数据），
 * 它最后那几行就会被<b>每 30 秒重投一次，永久</b>：
 *
 * <pre>
 *   2026-10-16 16:46:16  article 的 4 条公告写入，水位推进到 16:46:16
 *   16:46:46 起 每轮都回溯到 16:46:11，重新捞到这 4 行 → 重投 4 条事件，水位仍停在 16:46:16
 *   …8 条/分钟、480 条/小时，永不停止
 * </pre>
 *
 * 光看这个数字只是浪费。真正的代价是它与"永久失败的文档"叠在一起：
 * 那些事件在 ES 侧必然失败（例如 mapping 与字段对不上），于是每一条都在
 * {@code maxRetry} 之后转成死信，死信表以每小时数百条的速度增长，且与业务量无关 ——
 * <b>这个类型越安静，垃圾产生得越快</b>。
 *
 * <p>所以回溯改成"水位推进过的那一轮之后，再扫一次"：{@link SyncTask#getRecheckPending()}
 * 为 1 时才施加回溯，扫完这一轮按本轮水位是否前进过重设该标记。
 * 兜底能力基本不变（乱序落下的行仍会被那一次回溯捞到），而重复投递从
 * "每 30 秒一次、永久" 变成 "水位每次推进后一次"。
 *
 * <p><b>仍然存在的缺口，如实写明</b>：晚提交超过一轮（默认 30 秒）的行还是会漏 ——
 * 比如一个开了两分钟的事务。这与 {@code overlapSeconds} 本身只有 5 秒是同一量级的兜底，
 * 不是这次改动引入的；要真正消灭它需要"提交时刻"而不是"业务时刻"参与排序，
 * 而那意味着改表结构与所有写入点，代价与收益不成比例。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncrementalSyncJob {

    private final SyncTaskMapper syncTaskMapper;
    private final SearchContentMapper searchContentMapper;
    private final IndexTypeConfigService indexTypeConfigService;
    private final IndexEventPublisher indexEventPublisher;
    private final DocumentConverter documentConverter;
    private final KumaSearchProperties properties;
    private final DistributedLock distributedLock;

    /**
     * 单次执行最多推进的批次数。
     *
     * <p>设上限是为了让积压很多时不要一次跑到底：长时间占用数据库连接与事务，
     * 会与在线业务争抢资源。剩下的积压由后续的调度周期继续消化。
     */
    private static final int MAX_BATCHES_PER_RUN = 20;

    /**
     * 增量同步调度。
     *
     * <p>默认 30 秒一轮。这个频率决定了「旁路修改的数据多久能被搜到」的上限，
     * 也就是需求里"增量同步延迟 < 1 分钟"这条指标的主要来源。
     */
    @Scheduled(fixedDelayString = "${kuma.sync.incremental-interval-millis:30000}")
    public void run() {
        if (!properties.getSync().isIncrementalEnabled()) {
            return;
        }

        String lockKey = CacheKeys.syncTaskLock("incremental-sync");
        String token = distributedLock.tryLock(lockKey, Duration.ofMinutes(10));
        if (token == null) {
            return;
        }

        try {
            List<SyncTask> tasks = syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>()
                    .eq(SyncTask::getTaskType, SyncTaskType.INCREMENT.name())
                    .eq(SyncTask::getStatus, 1));

            for (SyncTask task : tasks) {
                try {
                    syncOne(task);
                } catch (Exception e) {
                    // 一个类型的同步失败不能影响其它类型：它们的数据源与索引彼此独立。
                    log.error("增量同步失败，task={}，type={}", task.getTaskName(), task.getTypeCode(), e);
                    recordTaskResult(task, "失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("增量同步任务异常终止", e);
        } finally {
            distributedLock.unlock(lockKey, token);
        }
    }

    /**
     * 同步单个类型。
     *
     * <p>关键设计：<b>先投递事件、再推进水位</b>，且水位只在"这一批处理干净"之后才更新。
     * 反过来的话，若在投递事件和推进水位之间进程被杀，下次会从新水位开始 ——
     * 中间那批数据被永久跳过。而按现在的顺序，最坏情况是重复投递一批事件，重复是幂等的。
     */
    private int syncOne(SyncTask task) {
        String typeCode = task.getTypeCode();
        IndexDefinition def = indexTypeConfigService.resolveDefinition(typeCode);

        int batchSize = task.getBatchSize() != null && task.getBatchSize() > 0
                ? task.getBatchSize()
                : properties.getSync().getBatchSize();

        LocalDateTime cursorTime = task.getLastSyncTime();
        Long cursorId = task.getLastSyncId();

        int totalSynced = 0;
        int batches = 0;

        // 本次运行是否要施加"回溯窗口"。见类注释：它只在**上一轮水位推进过**时生效一次，
        // 扫完由本轮的推进情况重新决定。这样既兜住了乱序落下的行，又不会把同一批行永久重投。
        boolean applyOverlap = Integer.valueOf(1).equals(task.getRecheckPending());

        // 本轮水位是否真的前进过。回溯轮扫到的行全都 ≤ 水位，不会把它往前推 ——
        // 这正是"回溯不再自我延续"的关键，所以要用比较而不是直接赋值来推进。
        boolean advanced = false;

        while (batches < MAX_BATCHES_PER_RUN) {
            // 只有第一个批次才谈得上"回溯窗口"，之后的批次必须用精确水位。
            boolean overlapThisBatch = applyOverlap && batches == 0;
            List<SearchContent> rows = scanBatch(typeCode, cursorTime, cursorId, overlapThisBatch, batchSize);
            if (rows.isEmpty()) {
                break;
            }

            List<IndexEvent> events = new ArrayList<>(rows.size());
            for (SearchContent row : rows) {
                events.add(IndexEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .typeCode(typeCode)
                        .bizId(row.getBizId())
                        .opType(OutboxOpType.UPSERT)
                        .payload(documentConverter.toDocumentJson(def, row))
                        .occurredAt(LocalDateTime.now())
                        .build());
            }

            indexEventPublisher.publishBatch(events);

            // 事件已投递，现在才推进水位 —— 且只在位置真的前进了的时候。
            //
            // 【为什么必须比较而不是赋值】回溯轮捞回的是"水位之前"的行，
            // 直接赋值等于把水位写成它已有的值（甚至更小），水位从此永远停在那一秒，
            // 于是每一轮都重扫重投同一批行。判据用复合游标的字典序，与 scanBatch 的条件同序。
            SearchContent last = rows.get(rows.size() - 1);
            if (isAfterCursor(last.getUpdateTime(), last.getId(), cursorTime, cursorId)) {
                cursorTime = last.getUpdateTime();
                cursorId = last.getId();
                advanced = true;
            }
            totalSynced += rows.size();
            batches++;

            if (rows.size() < batchSize) {
                // 不足一批说明已经追平，可以安全地把水位落库。
                break;
            }
        }

        // 无论本轮有没有同步到东西都落一次结果。
        //
        // 【原先只在 totalSynced > 0 时更新，是个观测缺陷】扫到 0 条时整段 update 被跳过，
        // 于是 last_run_time 停在"最后一次真的同步到数据"的那一刻 —— 一个安静的类型
        // 在后台看起来就是"这个任务已经死了"，而它其实每 30 秒都在正常工作。
        // 水位写回原值是幂等的 no-op，多写的只有执行时间与结果摘要。
        SyncTask update = new SyncTask();
        update.setId(task.getId());
        update.setLastSyncTime(cursorTime);
        update.setLastSyncId(cursorId);
        update.setRecheckPending(advanced ? 1 : 0);
        update.setLastRunTime(LocalDateTime.now());
        update.setLastResult("成功，本次同步 " + totalSynced + " 条，批次 " + batches);
        syncTaskMapper.updateById(update);

        log.info("增量同步完成，type={}，条数={}，批次={}，水位={}/{}，本轮前进={}，下轮回溯={}",
                typeCode, totalSynced, batches, cursorTime, cursorId, advanced, advanced ? "是" : "否");
        return totalSynced;
    }

    /**
     * 复合游标 {@code (time, id)} 的字典序比较：{@code (aTime, aId) > (bTime, bId)} 时返回 true。
     *
     * <p>与 {@code scanBatch} 的查询条件同序 —— 两处必须一致，否则水位与扫描的边界会错位。
     * 水位为空（从未同步过）时，任何一行都算"前进"。
     */
    private boolean isAfterCursor(LocalDateTime aTime, Long aId, LocalDateTime bTime, Long bId) {
        if (aTime == null) {
            return false;
        }
        if (bTime == null) {
            return true;
        }
        int timeCompared = aTime.compareTo(bTime);
        if (timeCompared != 0) {
            return timeCompared > 0;
        }
        long leftId = aId == null ? 0L : aId;
        long rightId = bId == null ? 0L : bId;
        return leftId > rightId;
    }

    /**
     * 扫描一批待同步数据。
     *
     * <p>条件用 {@code (update_time > cursorTime) OR (update_time = cursorTime AND id > cursorId)}
     * 而不是简单的 {@code update_time >= cursorTime}：后者在每批都恰好从同一秒中间开始时，
     * 会把同一秒内的记录反复扫出来。用严格大于的组合条件，边界是精确的。
     *
     * @param applyOverlap 是否把下界往前挪 {@code overlapSeconds}（回溯窗口）。
     *                     只有"本轮该回溯"的第一个批次才传 true，语义与来历见类注释
     */
    private List<SearchContent> scanBatch(String typeCode, LocalDateTime cursorTime,
                                          Long cursorId, boolean applyOverlap, int batchSize) {
        LambdaQueryWrapper<SearchContent> wrapper = new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, typeCode)
                .eq(SearchContent::getStatus, 1);

        if (cursorTime != null) {
            // 下界是与 ORDER BY 同序的复合游标 (update_time, id)，构成严格字典序比较。
            LocalDateTime lowTime;
            Long lowId;
            if (applyOverlap) {
                // 回溯：把窗口整段重新扫一遍。
                //
                // 【为什么这里不能带 id 边界】以前两个分支共用 cursorId 作为边界，
                // 即 `update_time > cursorTime-overlap OR (update_time = cursorTime-overlap AND id > cursorId)`。
                // 但 cursorId 属于 cursorTime 那一行，而 id 是随插入时间递增的 ——
                // 于是"恰好落在回溯边界秒"的那些行，id 几乎必然小于 cursorId，被全部跳过。
                // 而回溯窗口存在的全部意义，正是为了兜住"这一秒里提交晚、当时没扫到"的行：
                // 边界上的行被跳过，等于把回溯做成了摆设。
                // 置 0 之后，边界秒内的行会被完整重扫一遍 —— 重复投递是幂等的（ES 的 _id 覆盖），
                // 漏扫不是。
                //
                // ⚠️ 回溯与"水位推进"必须配合使用：本方法捞回的行全都 ≤ 水位，
                // 所以 syncOne 那边不能拿它去赋值水位，否则水位永远不动、回溯每轮都重来。
                lowTime = cursorTime.minusSeconds(properties.getSync().getOverlapSeconds());
                lowId = 0L;
            } else {
                // 精确水位。**不能**在这里回溯：此时 cursorTime 已经是上一批最后一行的位置，
                // 再往前回溯 5 秒会重新捞回刚刚处理过的行 ——
                // 批次永远填满、水位永远追不上，直接变成死循环。
                lowTime = cursorTime;
                lowId = cursorId == null ? 0L : cursorId;
            }

            final LocalDateTime fLowTime = lowTime;
            final Long fLowId = lowId;
            wrapper.and(w -> w
                    .gt(SearchContent::getUpdateTime, fLowTime)
                    .or(inner -> inner
                            .eq(SearchContent::getUpdateTime, fLowTime)
                            .gt(SearchContent::getId, fLowId)));
        }

        wrapper.orderByAsc(SearchContent::getUpdateTime)
                .orderByAsc(SearchContent::getId)
                .last("LIMIT " + batchSize);

        List<SearchContent> rows = searchContentMapper.selectList(wrapper);
        return rows == null ? List.of() : rows;
    }

    /**
     * 手工触发某个类型的增量同步，供管理后台的「立即同步」按钮使用。
     *
     * <p>不受 {@code kuma.sync.incremental-enabled} 开关限制 ——
     * 那个开关是给"这个实例是否参与自动调度"用的，手工触发是人的明确意图。
     */
    public int syncNow(String typeCode) {
        SyncTask task = syncTaskMapper.selectOne(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTypeCode, typeCode)
                .eq(SyncTask::getTaskType, SyncTaskType.INCREMENT.name())
                .last("LIMIT 1"));

        if (task == null) {
            throw BizException.notFound(
                    "类型 " + typeCode + " 没有配置增量同步任务，请先在后台创建");
        }
        return syncOne(task);
    }

    private void recordTaskResult(SyncTask task, String result) {
        SyncTask update = new SyncTask();
        update.setId(task.getId());
        update.setLastRunTime(LocalDateTime.now());
        update.setLastResult(result);
        syncTaskMapper.updateById(update);
    }
}
