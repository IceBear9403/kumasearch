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
 * Outbox 表：写入链路与 ES 解耦，保证 MySQL 事务提交后索引事件不丢。
 *
 * <p>内容变更时，同一事务内既改 t_search_content 又在此表插入一条事件，
 * 事务原子性保证「数据改了但事件没记」不会发生。ES 侧由后台线程扫描此表异步投递，
 * 失败按 {@code retry_count} + {@code next_retry_time} 指数退避重试，超过阈值转死信由人工介入。
 * {@code event_id} 独立于主键，供幂等去重；{@code payload} 存投递所需的完整文档，
 * 避免消费者回查源表时读到更新后的数据导致顺序错乱。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_search_outbox")
public class SearchOutbox implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件唯一 ID，供消费端幂等去重，保证同一事件重复投递不产生副作用。 */
    @TableField("event_id")
    private String eventId;

    /** 目标索引类型编码。 */
    @TableField("type_code")
    private String typeCode;

    /** 目标业务主键。 */
    @TableField("biz_id")
    private String bizId;

    /** 操作类型：UPSERT 写入/更新、DELETE 删除，取值见 OutboxOpType。 */
    @TableField("op_type")
    private String opType;

    /** 投递负载 JSON 字符串，读写时由上层用 ObjectMapper 解析为 ES 文档。 */
    @TableField("payload")
    private String payload;

    /** 处理状态：0 待处理、1 成功、2 失败待重试、3 死信，取值见 OutboxStatus。 */
    @TableField("status")
    private Integer status;

    /** 已重试次数，用于指数退避与死信判定。 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 下次可重试时间，未到该时间不再扫描，避免失败事件空转打爆 ES。 */
    @TableField("next_retry_time")
    private LocalDateTime nextRetryTime;

    /** 最近一次失败原因，仅保留摘要供排查。 */
    @TableField("error_msg")
    private String errorMsg;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
