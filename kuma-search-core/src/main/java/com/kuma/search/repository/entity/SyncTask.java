package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 同步任务表：把「何时同步、从哪继续」的调度状态集中持久化，支持多实例与重启续跑。
 *
 * <p>三种任务类型对应不同恢复语义：增量靠 {@code last_sync_time}/{@code last_sync_id} 断点续传；
 * 全量按批重建；对账用于发现两边漂移。调度状态入库而非放内存，
 * 是为了让进程重启或水平扩容后仍能接着上次的位点跑，不重不漏。
 *
 * <p>增量任务的状态不止「水位」两项，还有 {@link #recheckPending} —— 它记录
 * 「上一轮水位推进过，所以这一轮要先回溯再扫」。水位与它是一组：
 * 只看水位会漏掉并发事务的提交乱序，只回溯又会让水位被钉住，见字段注释。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sync_task")
public class SyncTask implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 任务名称，用于运维识别与日志检索。 */
    @TableField("task_name")
    private String taskName;

    /** 目标索引类型编码。 */
    @TableField("type_code")
    private String typeCode;

    /** 任务类型：INCREMENT 增量、FULL 全量、RECONCILE 对账，取值见 SyncTaskType。 */
    @TableField("task_type")
    private String taskType;

    /** 增量同步水位：上次同步到的业务时间，作为下次扫描起点。 */
    @TableField("last_sync_time")
    private LocalDateTime lastSyncTime;

    /** 增量同步水位：上次同步到的业务主键，用于同一时间戳下精确续跑。 */
    @TableField("last_sync_id")
    private Long lastSyncId;

    /**
     * 待回溯标记：1 表示「上一轮水位推进过，所以这一轮扫描要先回溯 overlap 秒再扫」，0 表示用精确水位。
     *
     * <p>存在的意义是让回溯窗口<b>只生效一次</b>。回溯的作用是兜住「update_time 小于水位、
     * 但提交得更晚」的行（两个并发事务，短事务后提交、长事务先提交）；而回溯扫到的行
     * 必然都小于等于水位，于是水位无法前进 —— 如果每轮都回溯，同一批行会被<b>永久</b>重投，
     * 而且只要其中有一条注定失败（例如 mapping 与字段对不上），它就会变成一个
     * 与业务量无关的死信发生器。详见 {@code IncrementalSyncJob}。
     */
    @TableField("recheck_pending")
    private Integer recheckPending;

    /** 调度 cron 表达式，为空表示仅手工触发。 */
    @TableField("cron_expr")
    private String cronExpr;

    /** 单批处理条数，控制单次扫描对源库与 ES 的压力。 */
    @TableField("batch_size")
    private Integer batchSize;

    /** 任务状态：1 启用、0 停用。 */
    @TableField("status")
    private Integer status;

    /** 上次实际执行时间。 */
    @TableField("last_run_time")
    private LocalDateTime lastRunTime;

    /** 上次执行结果摘要，用于后台快速查看成败与原因。 */
    @TableField("last_result")
    private String lastResult;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
