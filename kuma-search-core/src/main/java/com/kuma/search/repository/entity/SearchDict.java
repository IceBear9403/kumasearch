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
 * 词典表：承载分词主词、停用词与同义词，是检索质量的「可运营旋钮」。
 *
 * <p>把词典做成表而非配置文件，是为了让运营能在线调整并即时生效：同义词让「手机→移动电话」
 * 能互召回，停用词压掉无区分度的噪声词，主词表则为自定义分词提供领域词。一个词一行、
 * 用 {@code dict_type} 区分用途，编辑后只需重建分词链即可生效，不必发版。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_search_dict")
public class SearchDict implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 词典类型：MAIN 主词、STOP 停用词、SYNONYM 同义词，取值见 DictType。 */
    @TableField("dict_type")
    private String dictType;

    /** 词条本身。 */
    @TableField("word")
    private String word;

    /** 同义词列表，仅 SYNONYM 类型使用，多个同义词以分隔符拼接。 */
    @TableField("synonyms")
    private String synonyms;

    /** 状态：1 启用、0 停用。停用后词典重建时会被跳过。 */
    @TableField("status")
    private Integer status;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
