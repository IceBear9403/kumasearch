package com.kuma.search.es.builder;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortMode;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.es.mapping.IndexDefinition;
import com.kuma.search.es.model.SearchParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 构造排序子句。
 *
 * <h3>两个必须处理好的细节</h3>
 * <ol>
 *   <li><b>白名单校验</b>：只有类型配置里 {@code sortable=true} 的字段可以排序。
 *       ES 对未开启 doc_values 的字段排序会退化为加载 fielddata，那是一个会吃掉大量堆内存的操作，
 *       放任用户传入任意字段名排序是一个可以打挂集群的入口。</li>
 *   <li><b>唯一性兜底（tiebreaker）</b>：{@code search_after} 翻页依赖「排序键全局唯一」这个前提。
 *       如果两个文档的排序键完全相同，翻页时会漏掉或者重复返回文档。
 *       因此本类总是在排序链尾部追加业务主键作为最后的比较维度。</li>
 * </ol>
 */
@Slf4j
@Component
public class SortBuilder {

    /**
     * 缺失值的排序占位。ES 的 {@code missing} 参数要求一个具体取值，
     * {@code "_last"} 是它的保留字面量，含义是「不管升序降序都排到最后」。
     */
    private static final FieldValue MISSING_LAST = FieldValue.of("_last");

    /**
     * 构造排序列表。
     *
     * @param def   类型定义，用于白名单校验与获取主键字段
     * @param param 检索参数
     * @return 非空的排序列表。即使调用方未指定任何排序，也会返回「按主键降序」，
     *         以保证任何查询结果都有确定且唯一的顺序
     */
    public List<SortOptions> build(IndexDefinition def, SearchParam param) {
        List<SortOptions> sorts = new ArrayList<>();

        // 用户显式指定的排序
        if (param.getSorts() != null) {
            for (SearchParam.SortSpec spec : param.getSorts()) {
                if (spec.getField() == null || spec.getField().isBlank()) {
                    continue;
                }
                sorts.add(toSortOption(def, spec));
            }
        }

        // 类型配置里的默认排序
        if (sorts.isEmpty() && def.getDefaultSort() != null) {
            for (var entry : def.getDefaultSort()) {
                String field = entry.get("field");
                String order = entry.getOrDefault("order", "desc");
                if (field == null || field.isBlank()) {
                    continue;
                }
                SearchParam.SortSpec spec = SearchParam.SortSpec.builder()
                        .field(field)
                        .order(order)
                        .build();
                sorts.add(toSortOption(def, spec));
            }
        }

        // 兜底：按业务主键倒序，保证顺序唯一且稳定
        appendTieBreaker(def, sorts);
        return sorts;
    }

    /**
     * 这批排序会不会让 ES 停止计算评分。
     *
     * <p>ES 的规则是：只要排序链里出现了<b>字段排序</b>（而非 {@code _score}），
     * 响应里的 {@code _score} 就是 null —— 除非请求显式带 {@code track_scores: true}。
     * 而 {@link #build} 总会追加一个主键兜底排序，所以「调用方没传任何排序」的常规检索
     * 实际上也落在这一支上：<b>本站检索的每一条命中都没有评分</b>。
     *
     * <p>这带来两个后果，第二个是要命的那个：
     * <ol>
     *   <li>结果卡片上的「评分」永远是空 —— 只是少一个数字；</li>
     *   <li>后端序列化配置是「忽略 null」，score 为 null 时这个键会从 JSON 里
     *       <b>整个消失</b>，前端拿到 {@code undefined} 后调 {@code score.toFixed(2)}
     *       会抛 TypeError，而 Vue 的渲染错误会中止整棵子树的 patch ——
     *       现象是「接口明明返回了数据，页面却卡住不渲染」。2026-10-17 实测踩中。</li>
     * </ol>
     *
     * <p>有 {@code _score} 排序时返回 false：那种情况下 ES 无论如何都会算分，
     * 不必再要求一次（{@code track_scores} 只对字段排序有意义）。
     */
    public boolean needsTrackScores(List<SortOptions> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            return false;
        }
        for (SortOptions sort : sorts) {
            if (sort.isField()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 追加唯一性兜底排序。
     *
     * <p>如果调用方已经显式按主键排序（且是最后一个排序键），就不重复追加 ——
     * 重复的排序键在 ES 里不报错，但会让 {@code search_after} 的游标语义变得难以理解。
     */
    private void appendTieBreaker(IndexDefinition def, List<SortOptions> sorts) {
        String pkField = def.getPkField();
        if (pkField == null || pkField.isBlank()) {
            log.warn("类型 {} 未配置主键字段，跳序翻页可能出现重复或遗漏", def.getTypeCode());
            return;
        }

        String concretePk = def.exactField(pkField);
        boolean alreadyLast = !sorts.isEmpty()
                && sorts.get(sorts.size() - 1).isField()
                && concretePk.equals(sorts.get(sorts.size() - 1).field().field());
        if (alreadyLast) {
            return;
        }

        sorts.add(SortOptions.of(s -> s.field(f -> f.field(concretePk).order(SortOrder.Desc))));
    }

    private SortOptions toSortOption(IndexDefinition def, SearchParam.SortSpec spec) {
        String field = spec.getField();

        // _score 是 ES 的内置排序键，不参与字段白名单校验
        if ("_score".equals(field)) {
            SortOrder order = parseOrder(spec.getOrder());
            return SortOptions.of(s -> s.score(sc -> sc.order(order)));
        }

        // _doc 是最高效的排序键（无需打分、无需加载字段），用于「无所谓顺序只要翻遍全量」的场景
        if ("_doc".equals(field)) {
            SortOrder order = parseOrder(spec.getOrder());
            return SortOptions.of(s -> s.doc(d -> d.order(order)));
        }

        if (!def.hasField(field)) {
            throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                    "排序字段不在类型 " + def.getTypeCode() + " 的配置中: " + field);
        }
        if (!def.isSortable(field)) {
            throw BizException.of(ResultCode.SEARCH_FIELD_NOT_ALLOWED,
                    "字段未开启排序能力（sortable=false）: " + field);
        }

        SortOrder order = parseOrder(spec.getOrder());
        SortMode mode = parseMode(spec.getMode());

        // TEXT 字段的 doc_values 是关闭的，排序必须落到它的 .keyword 子字段上，
        // 直接对主字段排序会得到 "Fielddata is disabled" 运行时错误。
        String concreteField = def.exactField(field);

        return SortOptions.of(s -> s.field(f -> {
            f.field(concreteField).order(order);
            if (mode != null) {
                f.mode(mode);
            }
            // 缺失该字段的文档排在最后，而不是被 ES 默认当成最小值排到最前 ——
            // 后者在「按销量降序」这类场景下会让没有销量字段的商品意外占据首位。
            // "_last" 是 ES 的专用取值，表示「无论升序降序都排在末尾」。
            f.missing(MISSING_LAST);
            return f;
        }));
    }

    private SortOrder parseOrder(String order) {
        if (order == null || order.isBlank()) {
            return SortOrder.Desc;
        }
        return "asc".equalsIgnoreCase(order.trim()) ? SortOrder.Asc : SortOrder.Desc;
    }

    private SortMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        return switch (mode.trim().toLowerCase()) {
            case "min" -> SortMode.Min;
            case "max" -> SortMode.Max;
            case "avg" -> SortMode.Avg;
            case "sum" -> SortMode.Sum;
            case "median" -> SortMode.Median;
            default -> null;
        };
    }
}
