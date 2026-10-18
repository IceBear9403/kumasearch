package com.kuma.search.dto.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一个**公开可检索的类型**，对应搜索页类型选择器里的一行。
 *
 * <h3>这个类只有两个字段，是刻意的</h3>
 * 它与 {@code IndexTypeConfig} 实体最大的区别是<b>没有</b> {@code field_config} 与
 * {@code search_config} —— 那两个 JSON 是索引的完整 mapping 与打分配置（每个字段怎么分词、
 * 权重多少、哪些字段参与检索）。这是实现细节，公开出去等于把索引结构交出去。
 *
 * <p>用返回类型而不是"记得别多返回字段"来保证这件事：只要公开接口的签名是这个类，
 * 泄漏内部配置就必须先改这里的字段声明，而那是会被人 review 到的动作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = """
        一个公开可检索的类型，对应搜索页类型选择器里的一行（GET /api/search/types 的元素）。
        只有两个字段是刻意的：不含 field_config / search_config（索引的完整 mapping 与打分配置属实现细节，
        公开出去等于交出索引结构）。注意 webpage（站外采集内容）不会出现在这个列表里，
        它走独立入口 POST /api/search/web。""")
public class SearchTypeDTO implements Serializable {

    /** 类型编码，检索时原样回传（{@code SearchRequestDTO.typeCode}）。 */
    @Schema(description = "类型编码，检索时原样回传，作为 SearchRequestDTO.typeCode。", example = "article")
    private String typeCode;

    /** 类型展示名，取 {@code t_index_type_config.type_name}。 */
    @Schema(description = "类型展示名，取 t_index_type_config.type_name；为空时回退为 typeCode。", example = "文章")
    private String typeName;
}
