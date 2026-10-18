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
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检索内容表：所有类型的内容统一入这张宽表，是同步到 ES 的数据源头与重建兜底。
 *
 * <p>不同类型共用一张表靠 {@code type_code} 区分，配上 {@code extra} JSON 装类型特有字段，
 * 好处是同步链路只需读一张表就能重建任意索引，坏处是列会膨胀——因此刻意只把
 * 高频检索/排序列（标题、分类、作者、价格、权重）提为独立列，其余类型私有字段一律进 extra。
 * {@code biz_id} 是业务系统里的原始主键，与自增 id 解耦，保证外部系统迁移时文档身份稳定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_search_content")
public class SearchContent implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属索引类型编码，与 t_index_type_config.type_code 对应。 */
    @TableField("type_code")
    private String typeCode;

    /** 业务系统原始主键，ES 文档 _id 由 类型+该值 派生，跨系统迁移时保持稳定。 */
    @TableField("biz_id")
    private String bizId;

    /** 标题，参与检索与高亮。 */
    @TableField("title")
    private String title;

    /** 正文，体积较大，仅入库供重建使用，检索走 ES。 */
    @TableField("content")
    private String content;

    /** 标签，多个以分隔符拼接存储，供 facet 与过滤。 */
    @TableField("tags")
    private String tags;

    /** 分类 ID，供分类筛选与聚合。 */
    @TableField("category_id")
    private String categoryId;

    /** 作者 ID，供作者维度过滤。 */
    @TableField("author_id")
    private String authorId;

    /** 价格，用 BigDecimal 承载以免金额精度丢失。 */
    @TableField("price")
    private BigDecimal price;

    /** 业务权重，参与查询期排序加权，值越大越靠前。 */
    @TableField("biz_weight")
    private Float bizWeight;

    /** 内容状态：1 有效、0 无效。写入 ES 前据此过滤。 */
    @TableField("status")
    private Integer status;

    /** 发布时间，供时间排序与范围过滤，与 create_time 语义不同。 */
    @TableField("publish_time")
    private LocalDateTime publishTime;

    /** 类型私有字段 JSON 字符串，读写时由上层用 ObjectMapper 解析成各类型的扩展字段。 */
    @TableField("extra")
    private String extra;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0 未删、1 已删。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
