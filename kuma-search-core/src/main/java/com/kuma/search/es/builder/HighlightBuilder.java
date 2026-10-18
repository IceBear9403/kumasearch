package com.kuma.search.es.builder;

import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.es.mapping.FieldConfig;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.model.SearchParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 构造高亮子句。
 *
 * <h3>为什么高亮片段需要单独取、不能自己截</h3>
 * 关键词在原文中的位置只有分析器知道（中文分词后的偏移与字符下标并不一一对应）。
 * 自己用 {@code indexOf} 在原文里找关键词，遇到分词边界（如「手机」被切成「手」「机」）
 * 就会找不到，或者高亮出错误的位置。所以高亮必须由 ES 返回。
 *
 * <h3>安全提示</h3>
 * 高亮结果是<b>用户数据 + 我们插入的标签</b>的拼接。前端渲染时若直接
 * {@code v-html}，文档内容里的 {@code <script>} 就会执行 —— 这是 XSS 的经典路径。
 * 正确做法见 {@code docs/06-security.md}：前端只信任我们插入的标签，
 * 其余内容必须先转义。本项目在服务端用固定的 {@code <em>} 标签，
 * 前端侧用 {@code HighlightText.vue} 组件按标签切分后再逐段渲染。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HighlightBuilder {

    private final KumaSearchProperties properties;

    /** 高亮标签。刻意用最朴素的 {@code <em>}，不带任何属性，便于前端安全地解析。 */
    private static final String PRE_TAG = "<em>";
    private static final String POST_TAG = "</em>";

    /**
     * 构造高亮配置。
     *
     * @return 没有任何字段需要高亮时返回 null —— ES 对空的高亮配置会报错
     */
    public Highlight build(IndexDefinition def, SearchParam param) {
        List<String> fields = resolveHighlightFields(def, param);
        if (fields.isEmpty()) {
            return null;
        }

        int summaryLength = properties.getSearch().getSummaryLength();

        return Highlight.of(h -> {
            h.preTags(PRE_TAG).postTags(POST_TAG)
                    // 片段大小按字符数给。中文的字符信息密度高于英文，
                    // 同样的字符数能表达更多内容，所以这里不需要按英文习惯放大。
                    .fragmentSize(summaryLength)
                    .numberOfFragments(1)
                    .fields(fields.stream()
                            .map(field -> java.util.Map.entry(field, HighlightField.of(f -> f)))
                            .collect(java.util.stream.Collectors.toMap(
                                    java.util.Map.Entry::getKey,
                                    java.util.Map.Entry::getValue,
                                    (a, b) -> a,
                                    java.util.LinkedHashMap::new)));
            return h;
        });
    }

    /**
     * 解析需要高亮的字段。
     *
     * <p>与检索字段一样，只有配置里 {@code highlight=true} 的字段才允许高亮 ——
     * 对 keyword 类型字段做高亮是无意义的（不分词就没有片段），
     * 而对未配置的字段高亮会让 ES 白白分析一遍原文。
     */
    private List<String> resolveHighlightFields(IndexDefinition def, SearchParam param) {
        if (param.getHighlightFields() != null && !param.getHighlightFields().isEmpty()) {
            List<String> allowed = new ArrayList<>();
            for (String field : param.getHighlightFields()) {
                boolean ok = def.field(field)
                        .map(f -> Boolean.TRUE.equals(f.getHighlight()))
                        .orElse(false);
                if (ok) {
                    allowed.add(field);
                } else {
                    log.debug("字段 {} 未开启高亮，已忽略", field);
                }
            }
            return allowed;
        }

        if (def.getDefaultHighlightFields() != null && !def.getDefaultHighlightFields().isEmpty()) {
            return def.getDefaultHighlightFields();
        }

        if (def.getFields() == null) {
            return List.of();
        }
        return def.getFields().stream()
                .filter(f -> Boolean.TRUE.equals(f.getHighlight()))
                .map(FieldConfig::getName)
                .toList();
    }
}
