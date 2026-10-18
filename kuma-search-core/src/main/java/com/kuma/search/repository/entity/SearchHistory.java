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
 * 用户搜索历史表：面向用户可见的「最近搜过」，与只用于分析的日志表职责分离。
 *
 * <p>单独于 t_search_log 存放，是因为两者生命周期与用途不同：日志可无限增长后归档，
 * 而历史需要按用户量快速去重取最近 N 条，还要支持用户主动删除，写多读少但查询模式完全不同，
 * 拆表才能各自加合适的索引而不互相拖累。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_search_history")
public class SearchHistory implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 ID，历史按用户隔离。 */
    @TableField("user_id")
    private Long userId;

    /** 检索的类型编码，历史可按类型分开展示。 */
    @TableField("type_code")
    private String typeCode;

    /** 历史关键词，同一用户+类型下按此去重。 */
    @TableField("keyword")
    private String keyword;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
