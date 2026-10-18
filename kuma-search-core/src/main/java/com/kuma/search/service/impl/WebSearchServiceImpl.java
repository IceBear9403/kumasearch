package com.kuma.search.service.impl;

import com.kuma.search.dto.req.SearchRequestDTO;
import com.kuma.search.dto.req.WebSearchRequestDTO;
import com.kuma.search.dto.resp.FacetDTO;
import com.kuma.search.dto.resp.HitDTO;
import com.kuma.search.dto.resp.SearchResponseDTO;
import com.kuma.search.dto.resp.WebHitDTO;
import com.kuma.search.dto.resp.WebSearchResponseDTO;
import com.kuma.search.service.SearchService;
import com.kuma.search.service.WebSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页采集检索的实现：把「受限的网页请求」翻译成「通用检索请求」，再把结果翻译回来。
 *
 * <p>整个类就是两次翻译，中间的一步（{@code SearchService.search}）是既有的、已被复用多次的链路。
 * <b>不要在这里写任何 ES 逻辑</b>，也不要为了「少一次转换」把通用请求直接暴露给 Controller
 * —— 那样 {@code status=1} 的强制注入就没了落点，见下。
 *
 * <h3>⚠️ 本类的安全核心：{@code status=1} 由服务端注入，调用方无法覆盖</h3>
 * 采集内容里混着<b>未审核</b>（0）与<b>已驳回</b>（2）的记录，它们同样在 ES 里
 * （写链路不按 status 过滤，见 {@code IndexEventHandler}）。
 * 因此「不展示未审核内容」这件事<b>完全依赖查询侧的过滤</b> ——
 * 一旦这里漏掉，未审核的第三方内容会立刻对公网可见。
 *
 * <p>它是不可绕过的，因为 {@link WebSearchRequestDTO} <b>根本没有</b> filters 字段：
 * 调用方传不了、也就覆盖不了。这正是那个 DTO 字段少的原因。
 *
 * <p>合并 {@code domains} 时用新集合而不是在同一个 Map 上加键，
 * 是为了让「服务端注入的条件」与「调用方提供的条件」在代码上始终是两件事：
 * 将来若要允许调用方传更多过滤维度，也只会加在 {@code extraFilters} 那一侧，
 * 不可能碰到 {@code status}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchServiceImpl implements WebSearchService {

    /** 与 {@code sql/data.sql} 里 {@code t_index_type_config} 的 webpage 行、以及 t_search_content.type_code 对应。 */
    private static final String TYPE_CODE_WEBPAGE = "webpage";

    /** 已发布（审核通过）。{@code t_search_content.status} 的取值。 */
    private static final String STATUS_PUBLISHED = "1";

    /** ES 字段名。与 webpage 类型的 {@code field_config} 逐字对应。 */
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_DOMAIN = "domain";
    private static final String FIELD_SOURCE_URL = "sourceUrl";

    /** 站点聚合最多返回多少个桶。给足一点，让「只看某个站」有得选。 */
    private static final int DOMAIN_FACET_SIZE = 20;

    private final SearchService searchService;

    @Override
    public WebSearchResponseDTO search(WebSearchRequestDTO request, Long userId, String clientIp) {
        SearchResponseDTO generic = searchService.search(toGeneric(request), userId, clientIp);
        return toWebResponse(generic);
    }

    /**
     * 网页请求 → 通用检索请求。
     *
     * <p>四个固定项的含义：
     * <ul>
     *   <li>{@code typeCode = webpage} —— 固定，不来自请求；</li>
     *   <li>{@code filters.status = ["1"]} —— 见类注释，本类的安全核心；</li>
     *   <li>{@code sorts = [_score desc]} —— <b>必须显式给</b>。
     *       不给的话，{@code SortBuilder} 会套用类型配置里的
     *       {@code defaultSort}（{@code bizWeight desc, publishTime desc}），
     *       于是「按相关度排序」这件事根本不会发生 —— 用户搜什么词，
     *       拿到的都是同一批权重最高的页面。这是网页检索里最容易出现的静默错误：
     *       结果不为空、看不出报错，只是搜什么都不准；</li>
     *   <li>{@code synonym/pinyin = false} —— 网页类型的 {@code field_config} 里
     *       这三个能力本来就是关的，请求侧再开一次只会让查询去引用不存在的子字段。</li>
     * </ul>
     */
    private SearchRequestDTO toGeneric(WebSearchRequestDTO request) {
        SearchRequestDTO generic = new SearchRequestDTO();
        generic.setTypeCode(TYPE_CODE_WEBPAGE);
        generic.setKeyword(request.getKeyword());
        generic.setPageNum(request.getPageNum() == null ? 1 : request.getPageNum());
        generic.setPageSize(request.getPageSize() == null ? 10 : request.getPageSize());

        // 服务端注入的条件。用独立的新 Map 构造，不复用调用方传进来的任何集合。
        Map<String, List<String>> filters = new LinkedHashMap<>();
        filters.put("status", List.of(STATUS_PUBLISHED));

        // 调用方提供的站点白名单，与服务端条件并列（都是 filter，AND 语义）
        if (request.getDomains() != null && !request.getDomains().isEmpty()) {
            List<String> domains = new ArrayList<>();
            for (String domain : request.getDomains()) {
                if (domain == null || domain.isBlank()) {
                    continue;
                }
                // 归一化：域名大小写不敏感，而 keyword 字段的 terms 是精确匹配，
                // 传 "Example.COM" 不加这一步会一条都搜不到
                domains.add(domain.trim().toLowerCase(java.util.Locale.ROOT));
            }
            if (!domains.isEmpty()) {
                filters.put(FIELD_DOMAIN, domains);
            }
        }
        generic.setFilters(filters);

        // 站点聚合：让前端能渲染「结果来自哪些站点」
        SearchRequestDTO.FacetDTO domainFacet = new SearchRequestDTO.FacetDTO();
        domainFacet.setField(FIELD_DOMAIN);
        domainFacet.setSize(DOMAIN_FACET_SIZE);
        generic.setFacets(List.of(domainFacet));

        SearchRequestDTO.SortDTO byScore = new SearchRequestDTO.SortDTO();
        byScore.setField("_score");
        byScore.setOrder("desc");
        generic.setSorts(List.of(byScore));

        generic.setFuzzy(true);
        generic.setSynonym(false);
        generic.setPinyin(false);
        generic.setUseCursor(false);
        generic.setTrackTotalHits(true);
        return generic;
    }

    /**
     * 通用响应 → 网页响应。
     *
     * <p>{@code fields} 是「字段名 → 任意值」的 Map，取值可能是单个值，
     * 也可能是数组 —— 这取决于 {@code EsSearchGateway} 取的是 {@code _source} 还是
     * {@code fields}（两者的返回形态不同）。这里用 {@link #firstString} 同时兼容，
     * 而不是去猜是哪一种：那种依赖一旦哪天改了就变成静默的字段丢失。
     */
    private WebSearchResponseDTO toWebResponse(SearchResponseDTO generic) {
        List<WebHitDTO> hits = new ArrayList<>();
        if (generic.getHits() != null) {
            for (HitDTO hit : generic.getHits()) {
                hits.add(toWebHit(hit));
            }
        }
        return WebSearchResponseDTO.builder()
                .hits(hits)
                .total(generic.getTotal())
                .pageNum(generic.getPageNum())
                .pageSize(generic.getPageSize())
                .pages(generic.getPages())
                .nextCursor(generic.getNextCursor())
                .cursorMode(generic.getCursorMode())
                .domainFacets(extractDomainFacets(generic.getFacets()))
                .tookMillis(generic.getTookMillis())
                .partial(generic.getPartial())
                .build();
    }

    private WebHitDTO toWebHit(HitDTO hit) {
        Map<String, Object> fields = hit.getFields() == null ? Map.of() : hit.getFields();
        return WebHitDTO.builder()
                .id(hit.getId())
                .title(firstString(fields.get(FIELD_TITLE)))
                .sourceUrl(firstString(fields.get(FIELD_SOURCE_URL)))
                .domain(firstString(fields.get(FIELD_DOMAIN)))
                // 优先用服务端生成的摘要；没有就退回 content 字段（存的就是摘要）
                .summary(hit.getSummary() != null ? hit.getSummary() : firstString(fields.get(FIELD_CONTENT)))
                .highlights(hit.getHighlights())
                .score(hit.getScore())
                .build();
    }

    /**
     * 从通用 facet 结果里取出 domain 那一组。
     *
     * <p>按字段名查找而不是取第一个：请求里只配了 domain 一个 facet，
     * 但「只配了一个所以取第 0 个」是那种在加第二个 facet 时立刻失效、
     * 而且失效方式是把 A 的数据渲染成 B 的静默写法。
     */
    private List<FacetDTO.BucketDTO> extractDomainFacets(List<FacetDTO> facets) {
        if (facets == null) {
            return List.of();
        }
        for (FacetDTO facet : facets) {
            if (FIELD_DOMAIN.equals(facet.getField())) {
                return facet.getBuckets() == null ? List.of() : facet.getBuckets();
            }
        }
        return List.of();
    }

    /**
     * 从可能是字符串、也可能是列表的值里取出第一个非空字符串。
     *
     * <p>不做 {@code toString()} 一把梭：那会把 {@code List} 直接渲染成
     * {@code [a, b]} 这样的字面量字符串送到前端，而前端只会把它当普通文本显示。
     */
    private String firstString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isEmpty()) {
                    return s;
                }
            }
            return null;
        }
        return String.valueOf(value);
    }
}
