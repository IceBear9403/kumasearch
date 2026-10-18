package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.service.SyncTaskService;
import com.kuma.search.common.result.R;
import com.kuma.search.repository.entity.SyncTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 同步任务管理接口。整个控制器需要权限码 {@code admin:sync:manage}。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sync-task")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:sync:manage')")
@Tag(name = "同步任务", description = """
        同步任务的增删改查与手动触发。任务类型 `task_type` 取 `INCREMENT`（增量）/ `FULL`（全量重建）/ `RECONCILE`（对账）。

        **本组全部端点需要权限码 `admin:sync:manage`。**

        手动触发 `POST /{id}/run` 是同步执行并返回执行结果摘要的；`FULL` 类型走的是完整的索引重建流程。
        """)
@SecurityRequirement(name = "bearerAuth")
public class SyncTaskController {

    private final SyncTaskService syncTaskService;

    /** 列出全部同步任务。 */
    @GetMapping
    @Operation(summary = "列出同步任务", description = """
            返回全部同步任务，按 id 倒序（最近创建的在前）。""")
    public R<List<SyncTask>> list() {
        return R.ok(syncTaskService.list());
    }

    /** 新建同步任务。 */
    @PostMapping
    @Operation(summary = "新建同步任务", description = """
            落库一条同步任务并返回主键 id。
            task_type 取值 INCREMENT / FULL / RECONCILE，决定被触发时走哪条执行链路。""")
    public R<Long> create(@RequestBody SyncTask task) {
        return R.ok(syncTaskService.create(task));
    }

    /** 更新同步任务。坑：{@code id} 必填。 */
    @PutMapping
    @Operation(summary = "更新同步任务", description = """
            按 id 更新，body 必须带 id，否则返回参数错误。
            未传的字段按 MyBatis-Plus 语义不参与更新（不会把已有值置空）。""")
    public R<Void> update(@RequestBody SyncTask task) {
        syncTaskService.update(task);
        return R.ok();
    }

    /** 删除同步任务。 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除同步任务", description = """
            按主键 id 删除同步任务。""")
    public R<Void> delete(
            @Parameter(description = "同步任务主键 id", example = "1") @PathVariable Long id) {
        syncTaskService.delete(id);
        return R.ok();
    }

    /**
     * 手动触发一次同步任务。
     *
     * <p><b>同步执行</b>：这个接口会等到任务真正跑完才返回，因此可能耗时数秒（全量重建更久）。
     * 返回的是执行结果摘要（同步条数、耗时等），后台可直接展示，不必再查一次列表。
     *
     * <p>任务停用时返回参数错误而不是静默跳过 —— 「停用」是明确的运维意图，
     * 手动触发不应该能绕过它。
     */
    @PostMapping("/{id}/run")
    @Operation(summary = "手动触发同步任务", description = """
            手动执行指定任务并返回执行结果摘要（taskId / taskName / typeCode / taskType / costMillis 及各项结果字段），执行后会回写 lastRunTime。
            同步执行：接口会阻塞到任务真正跑完才返回，可能耗时数秒（FULL 类型走完整索引重建流程更久），不要按普通请求设超时。
            FULL 类型的任务执行的是完整的索引重建流程（建新索引 → 灌数据 → 校验 → 切别名）。
            任务已停用时不会静默跳过，而是直接返回参数错误（停用是明确的运维意图，手动触发不应能绕过）。
            注意：任务执行过程中的异常会被就地捕获并以 success=false 写入返回体（HTTP 仍为成功），调用方需据 success 字段判成败。""")
    public R<Map<String, Object>> run(
            @Parameter(description = "同步任务主键 id", example = "1") @PathVariable Long id) {
        return R.ok(syncTaskService.run(id));
    }
}
