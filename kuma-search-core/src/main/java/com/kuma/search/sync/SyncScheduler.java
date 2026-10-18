package com.kuma.search.sync;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.constant.SyncTaskType;
import com.kuma.search.repository.entity.SyncTask;
import com.kuma.search.repository.mapper.SyncTaskMapper;
import com.kuma.search.service.DynamicIndexManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 同步任务的统一触发入口。
 *
 * <h3>为什么需要一个"调度器"，而不是让管理后台直接调各个 Job</h3>
 * 后台的任务列表里，一行记录只知道自己的 {@code task_type} 是 INCREMENT / FULL / RECONCILE，
 * 它不知道这三者分别由哪个类实现。如果让 Controller 自己写 if-else 去分发，
 * 那么每新增一种任务类型都要改 Controller —— 而 Controller 是最不该承载业务分支的地方。
 *
 * <p>把分发收在这里，新增任务类型时只改这一个 switch。管理后台只需调
 * {@link #runTask(Long)}，完全不感知底层有哪些 Job。
 *
 * <h3>与各个 Job 里的 {@code @Scheduled} 的关系</h3>
 * 自动调度由各个 Job 自己声明（它们更清楚自己的执行周期），本类只负责
 * <b>手工触发</b>这一条路径。两条路径最终都会走分布式锁，
 * 因此手工点一次"立即同步"与自动调度撞在一起也不会重复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final SyncTaskMapper syncTaskMapper;
    private final IncrementalSyncJob incrementalSyncJob;
    private final ReconcileJob reconcileJob;
    private final DynamicIndexManager dynamicIndexManager;

    /**
     * 手工执行一个同步任务。
     *
     * <p><b>同步执行、不返回任务 id</b>：管理后台点"立即同步"时期待的是
     * "等它跑完再刷新列表看到结果"。若改成异步投递，后台需要额外做一次轮询才能知道结果，
     * 而单次同步的耗时有界（批次数有上限），同步等待是可以接受的。
     *
     * @param taskId 同步任务的 id
     * @return 执行结果摘要，用于在后台直接展示
     */
    public Map<String, Object> runTask(Long taskId) {
        SyncTask task = syncTaskMapper.selectById(taskId);
        if (task == null) {
            throw BizException.notFound("同步任务不存在: id=" + taskId);
        }
        return run(task);
    }

    /**
     * 手工执行一个类型下指定种类的同步任务。
     *
     * <p>提供这个重载是因为"立即同步"在业务上常常按<b>类型</b>发起
     * （"把商品的索引刷新一下"），而不是按任务 id。
     *
     * @param typeCode 类型编码
     * @param taskType 任务类型，取值见 {@link SyncTaskType}
     */
    public Map<String, Object> runTask(String typeCode, SyncTaskType taskType) {
        SyncTask task = syncTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncTask>()
                        .eq(SyncTask::getTypeCode, typeCode)
                        .eq(SyncTask::getTaskType, taskType.name())
                        .last("LIMIT 1"));

        if (task == null) {
            throw BizException.notFound(
                    "类型 " + typeCode + " 没有配置 " + taskType.name() + " 类型的同步任务");
        }
        return run(task);
    }

    private Map<String, Object> run(SyncTask task) {
        SyncTaskType taskType = parseType(task.getTaskType());
        long startedAt = System.currentTimeMillis();

        log.info("手工触发同步任务，task={}，type={}，taskType={}",
                task.getTaskName(), task.getTypeCode(), taskType);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getId());
        result.put("taskName", task.getTaskName());
        result.put("typeCode", task.getTypeCode());
        result.put("taskType", taskType.name());

        try {
            switch (taskType) {
                case INCREMENT -> {
                    int synced = incrementalSyncJob.syncNow(task.getTypeCode());
                    result.put("syncedCount", synced);
                    result.put("message", "增量同步完成，共同步 " + synced + " 条");
                }
                case FULL -> {
                    // 全量同步走的是完整的重建流程（建新索引 → 灌数据 → 校验 → 切别名），
                    // 而不是"清空当前索引再灌"——后者会让搜索在重建期间不可用。
                    dynamicIndexManager.rebuildIndex(task.getTypeCode());
                    result.put("message", "全量重建已提交并完成，索引已切换");
                }
                case RECONCILE -> {
                    ReconcileJob.ReconcileResult reconcileResult =
                            reconcileJob.reconcile(task.getTypeCode());
                    result.put("reconcile", reconcileResult);
                    result.put("message", reconcileResult.isConsistent()
                            ? "对账通过，未发现差异"
                            : "对账发现差异: " + reconcileResult.getMessage());
                }
                default -> throw BizException.of(
                        com.kuma.search.common.result.ResultCode.SYSTEM_ERROR,
                        "不支持的任务类型: " + taskType);
            }
            result.put("success", true);
        } catch (Exception e) {
            log.error("手工触发的同步任务执行失败，task={}", task.getTaskName(), e);
            result.put("success", false);
            result.put("message", "执行失败: " + e.getMessage());
        }

        result.put("costMillis", System.currentTimeMillis() - startedAt);
        recordResult(task, result);
        return result;
    }

    /**
     * 把执行结果写回任务表。
     *
     * <p>手工触发也必须落库，否则后台列表里"上次执行时间"永远不更新，
     * 运维会误以为任务没有被触发过而反复点击。
     */
    private void recordResult(SyncTask task, Map<String, Object> result) {
        try {
            SyncTask update = new SyncTask();
            update.setId(task.getId());
            update.setLastRunTime(LocalDateTime.now());
            update.setLastResult(String.valueOf(result.get("message")));
            syncTaskMapper.updateById(update);
        } catch (Exception e) {
            // 结果回写失败不影响本次执行的结果，只是列表上少了一次记录
            log.warn("同步任务结果回写失败，task={}，原因={}", task.getTaskName(), e.getMessage());
        }
    }

    private SyncTaskType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.of(com.kuma.search.common.result.ResultCode.PARAM_INVALID,
                    "同步任务缺少 task_type");
        }
        try {
            return SyncTaskType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw BizException.of(com.kuma.search.common.result.ResultCode.PARAM_INVALID,
                    "无法识别的任务类型: " + raw);
        }
    }
}
