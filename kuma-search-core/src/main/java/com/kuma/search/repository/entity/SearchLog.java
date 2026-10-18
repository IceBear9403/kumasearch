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
 * 检索日志表：只写不读的高频明细，用于效果分析与问题回溯。
 *
 * <p>记录原始词与归一化词两个字段，是为了区分「用户真实输入」和「系统实际检索的词」，
 * 归因分词/同义词改动带来的效果变化时两者缺一不可。
 * 只保留 create_time、不设更新列，因为日志一经写入即不可变，这样表可以按时间分区、低成本滚动清理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_search_log")
public class SearchLog implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 发起检索的用户 ID，未登录为 null。 */
    @TableField("user_id")
    private Long userId;

    /** 检索的目标类型编码。 */
    @TableField("type_code")
    private String typeCode;

    /** 用户原始输入关键词，保留现场用于复现。 */
    @TableField("keyword")
    private String keyword;

    /** 归一化后的关键词（去停用词/转小写/同义词替换后），即真正送检的词。 */
    @TableField("normalized")
    private String normalized;

    /** 命中结果数，为 0 的高频词是无结果优化的入口。 */
    @TableField("result_count")
    private Integer resultCount;

    /** 本次检索耗时（毫秒），用于监控慢查询与性能回归。 */
    @TableField("cost_ms")
    private Integer costMs;

    /** 客户端 IP。 */
    @TableField("ip")
    private String ip;

    /** 请求链路 ID，与网关/应用日志关联以做全链路追踪。 */
    @TableField("request_id")
    private String requestId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
