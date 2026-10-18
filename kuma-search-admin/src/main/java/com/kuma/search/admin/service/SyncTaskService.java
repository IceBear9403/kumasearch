package com.kuma.search.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.repository.entity.SyncTask;
import com.kuma.search.repository.mapper.SyncTaskMapper;
import com.kuma.search.sync.SyncScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 同步任务管理（admin 专有）。
 *
 * <p>CRUD 落库；手动触发委托给 core 的 {@link SyncScheduler} ——
 * 真正的执行逻辑（读游标、拉数据、投事件、推进水位）都在 core，
 * admin 只负责"找到任务、校验状态、把 id 递过去"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskService {

    /** 任务状态：1 启用 / 0 停用，与 t_sync_task.status 一致。 */
    private static final int STATUS_ENABLED = 1;

    private final SyncTaskMapper syncTaskMapper;
    private final SyncScheduler syncScheduler;

    /** 列出全部同步任务，按 ID 倒序（最近创建的在前）。 */
    public List<SyncTask> list() {
        return syncTaskMapper.selectList(new LambdaQueryWrapper<SyncTask>().orderByDesc(SyncTask::getId));
    }

    public Long create(SyncTask task) {
        task.setId(null);
        syncTaskMapper.insert(task);
        log.info("新建同步任务 taskName={} typeCode={}", task.getTaskName(), task.getTypeCode());
        return task.getId();
    }

    public void update(SyncTask task) {
        if (task.getId() == null) {
            throw BizException.paramInvalid("id 不能为空");
        }
        syncTaskMapper.updateById(task);
    }

    public void delete(Long id) {
        syncTaskMapper.deleteById(id);
    }

    /**
     * 手动触发一次同步任务，返回执行结果摘要供后台直接展示。
     *
     * <p><b>同步等待、不返回任务 id</b>：管理后台点"立即同步"时期待的是"等它跑完再刷新
     * 列表就能看到结果"。若改成异步投递，后台还得额外轮询一次才知道成功与否，
     * 而单次同步的耗时有界（批次数有上限），同步等待是可以接受的。
     *
     * <p>触发前校验任务存在且处于启用状态。校验停用状态是有实际意义的：
     * 后台的"停用"是一个明确的运维意图（比如该类型正在改配置、先别同步），
     * 若手动触发能绕过它，这个开关就形同虚设。
     *
     * @return 执行结果摘要（taskId / taskName / typeCode / taskType / costMs / 同步条数等）
     * @throws BizException 任务不存在，或任务已停用
     */
    public Map<String, Object> run(Long id) {
        SyncTask task = syncTaskMapper.selectById(id);
        if (task == null) {
            throw BizException.notFound("同步任务不存在: id=" + id);
        }
        if (task.getStatus() == null || task.getStatus() != STATUS_ENABLED) {
            throw BizException.paramInvalid(
                    "同步任务「" + task.getTaskName() + "」已停用，请先启用后再触发");
        }
        log.info("手动触发同步任务，taskId={}，taskName={}", id, task.getTaskName());
        // 执行逻辑（读游标、拉数据、投事件、推进水位、回写 lastRunTime）全在 core，
        // admin 只负责找到任务并递 id。SyncScheduler 内部已按 task_type 分派到对应的 Job。
        return syncScheduler.runTask(id);
    }
}
