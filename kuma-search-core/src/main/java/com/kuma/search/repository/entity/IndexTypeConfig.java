package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 索引类型配置表：多类型搜索引擎的「类型即配置」落点，一类业务内容一行。
 *
 * <p>每行描述一个 ES 索引该长什么样——字段、分词、检索方式。把它放在 MySQL 而不是写死在代码里，
 * 是为了让新增内容类型只需在后台配置 + 触发一次重建，无需改代码重新发版。
 * {@code indexAlias} + {@code currentVersion} 是蓝绿重建的关键：检索始终打别名，
 * 重建时新建带版本号的物理索引，切换别名即完成原子上线，旧索引可随时回滚。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_index_type_config")
public class IndexTypeConfig implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 类型编码，业务侧写入与检索时引用的唯一标识，如 article、product。 */
    @TableField("type_code")
    private String typeCode;

    /** 类型显示名。 */
    @TableField("type_name")
    private String typeName;

    /** 索引别名，检索端只认别名，物理索引切换对它透明。 */
    @TableField("index_alias")
    private String indexAlias;

    /** 当前生效的物理索引版本号，别名指向该版本。 */
    @TableField("current_version")
    private Integer currentVersion;

    /** 主分片数，创建索引后不可改，调整需重建。 */
    @TableField("shards")
    private Integer shards;

    /** 每分片副本数，可在线调整。 */
    @TableField("replicas")
    private Integer replicas;

    /** 业务主键在源表中的字段名，用于 upsert/delete 时定位文档。 */
    @TableField("pk_field")
    private String pkField;

    /** 字段配置 JSON 字符串，读写时由上层用 ObjectMapper 解析为字段配置列表。 */
    @TableField("field_config")
    private String fieldConfig;

    /** 检索配置 JSON 字符串（默认检索字段、facet、排序等），读写时由上层用 ObjectMapper 解析。 */
    @TableField("search_config")
    private String searchConfig;

    /** 状态：1 启用、0 停用。停用后不再受理该类型的写入与检索。 */
    @TableField("status")
    private Integer status;

    /**
     * 是否允许前台用户向该类型投稿：1 允许、0 不允许。
     *
     * <p>默认 0，且是**逐个类型**显式开启的：投稿接口是一个「任何登录用户都能往里写」的入口，
     * 若默认开放，新建一个类型就等于同时开了一个公开写入入口，而人不会意识到自己开了它。
     *
     * <p>它同时回答了「typeCode 到底是什么意思」这个问题的一半：typeCode 不只是内容的分类，
     * 它是一份字段配置 + 一个 ES 索引 + 一套权限口径（谁能写、谁能审）。所以「让用户能投稿」
     * 这件事的粒度天然就是类型，而不是全局开关。
     */
    @TableField("allow_user_submit")
    private Integer allowUserSubmit;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0 未删、1 已删。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
