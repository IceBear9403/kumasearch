package com.kuma.search.admin.controller.search;

import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.admin.security.RateLimitGuard;
import com.kuma.search.admin.security.RateLimitKeys;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.dto.req.SearchRequestDTO;
import com.kuma.search.dto.req.WebSearchRequestDTO;
import com.kuma.search.dto.resp.SearchResponseDTO;
import com.kuma.search.dto.resp.SearchTypeDTO;
import com.kuma.search.dto.resp.SuggestDTO;
import com.kuma.search.dto.resp.WebSearchResponseDTO;
import com.kuma.search.service.HotWordService;
import com.kuma.search.service.IndexTypeConfigService;
import com.kuma.search.service.SearchService;
import com.kuma.search.service.SuggestService;
import com.kuma.search.service.WebSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索接口。
 *
 * <h3>鉴权分层（本次改动最需要理解的一件事）</h3>
 * 检索本身<b>匿名可用</b>：{@code POST /api/search}、{@code /api/search/web}、
 * {@code GET /api/search/suggest}、{@code GET /api/search/hot}、
 * {@code GET /api/search/types} 都没有
 * {@code @PreAuthorize}，{@link com.kuma.search.admin.security.SecurityConfig} 里也逐条放行了它们。
 *
 * <p>只有历史的读写（{@code GET/DELETE /api/search/history}）仍挂
 * {@code search:read} —— 它们天生属于个人数据。因此 {@code search:read} 的语义
 * <b>收窄为「能读写自己的搜索历史」</b>，不再是「能搜索」。
 * 权限码本身没有新增，见 {@code docs/04-api-design.md} 的权限码矩阵。
 *
 * <h3>匿名可用必然带来的代价：限流</h3>
 * 一个无门槛、无上限、背后接着 ES 的公开接口，必须自己扛住流量。
 * 本类每个公开端点都先过 {@link RateLimitGuard}，
 * <b>这是匿名化的正确代价，不是可选的优化</b>：少了它，一个脚本就能把 ES 打到超时，
 * 受害的是所有正常用户。
 *
 * <p>限流的 key 一律用 IP，而 IP 来自 {@link #clientIp} —— 那里返回的是
 * {@code getRemoteAddr()}，其可信性由 Tomcat 的 RemoteIpValve（{@code internal-proxies}）保证。
 * <b>不要改回直接读 {@code X-Forwarded-For}</b>，那等于让攻击者自己决定被计入哪个桶。
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "检索", description = """
        站内检索、网页采集检索、联想、热词、类型列表与个人搜索历史。

        **本组大部分端点匿名可用，不需要登录** —— 这是产品需求，不是漏配。
        只有 `GET/DELETE /api/search/history`（个人搜索历史）需要登录，
        它在文档页上带锁图标。

        匿名端点全部按 IP 限流，超限返回 `10003` / HTTP 429。
        限流的 IP 取自 socket 地址而非请求头（见 `SearchController#clientIp`），
        伪造 `X-Forwarded-For` 绕不过去。
        """)
public class SearchController {

    private final SearchService searchService;
    private final WebSearchService webSearchService;
    private final SuggestService suggestService;
    private final HotWordService hotWordService;
    /** 只用来取「可检索类型列表」；管理侧的读写走 {@code IndexTypeController}，两者权限完全不同。 */
    private final IndexTypeConfigService indexTypeConfigService;
    private final RateLimitGuard rateLimitGuard;
    private final KumaSearchProperties properties;

    /**
     * 站内检索。匿名可用，按 IP 限流。
     *
     * <p>{@code userId} 取自 SecurityContext（未登录为 null，仅影响历史与日志，不影响结果）。
     */
    @Operation(summary = "站内检索", description = """
            匿名可用，按 IP 限流。

            未登录也能拿到完整结果：`userId` 为空只影响「搜索历史」与埋点日志，不影响检索本身。

            **空结果要看 `partial` 字段**：`partial = true` 表示这次查询没能真正执行
            （该类型的索引尚未创建，后端按空结果降级返回了），
            不能据此说「没有找到相关内容」；`partial` 缺失或为 `false` 时，
            `hits` 为空才是真的没搜到。""")
    @PostMapping
    public R<SearchResponseDTO> search(@Valid @RequestBody SearchRequestDTO request,
                                       HttpServletRequest httpRequest) {
        limit(RateLimitKeys.DIM_SEARCH_IP, properties.getRateLimit().getSearch(), httpRequest);
        Long userId = LoginUser.currentUserId();
        return R.ok(searchService.search(request, userId, clientIp(httpRequest)));
    }

    /**
     * 网页采集内容检索（对外措辞见 {@code docs/01-requirements.md} 的边界说明）。
     *
     * <p>与站内检索<b>分成两个接口、两个限流桶</b>，而不是给 {@link SearchRequestDTO}
     * 加一个"包含全网"的开关。理由写在 {@code WebSearchRequestDTO} 的类注释里：
     * 两侧的参数与响应结构差异大到塞进一个 DTO 会让两边都变形。
     *
     * <p>分成两个请求还有一个可用性上的好处：任一侧失败可独立降级，
     * 站内结果先渲染，网页区块显示"加载失败"，不会一损俱损。
     *
     * <p>{@code userId} 在网页检索里<b>不参与任何逻辑</b>，传它只是为了让实现签名
     * 与站内检索保持一致，将来若要按用户做排序偏好时不必改接口。
     */
    @Operation(summary = "网页采集检索", description = """
            匿名可用，按 IP 限流，与站内检索**各有独立的限流桶**。

            检索的是本站按种子站白名单采回来的语料，不是全网 ——
            对外措辞见 docs/01-requirements.md 的边界说明。

            只有**人工审核放行**过的采集内容才会出现在结果里，未审核的查不到。
            结果里只有标题 + 摘要 + 原文链接，点进去跳原站，本站不落全文。""")
    @PostMapping("/web")
    public R<WebSearchResponseDTO> webSearch(@Valid @RequestBody WebSearchRequestDTO request,
                                             HttpServletRequest httpRequest) {
        limit(RateLimitKeys.DIM_WEB_SEARCH_IP, properties.getRateLimit().getSearch(), httpRequest);
        Long userId = LoginUser.currentUserId();
        return R.ok(webSearchService.search(request, userId, clientIp(httpRequest)));
    }

    /**
     * 搜索联想。匿名可用，按 IP 限流。
     *
     * <p>阈值必须显著宽于检索：前端<b>每次击键</b>都会触发它，
     * 用检索的阈值会让正常打字的人在前几个字就被拦。
     *
     * <p>坑：{@code prefix} 为空时仍应返回热门/历史建议（由下游服务决定），不要在这里报错。
     */
    @Operation(summary = "搜索联想", description = """
            匿名可用，按 IP 限流；**阈值远宽于检索**，因为前端每次击键都会触发它。

            `prefix` 为空是合法输入，此时返回热门/历史建议而不是报错 ——
            这是搜索框获得焦点时的正常请求。""")
    @GetMapping("/suggest")
    public R<List<SuggestDTO>> suggest(
            @Parameter(description = "类型编码，取值见 GET /api/search/types", example = "article")
            @RequestParam String typeCode,
            @Parameter(description = "用户已输入的前缀。可为空，为空时返回热门/历史建议", example = "搜索")
            @RequestParam(required = false) String prefix,
            @Parameter(description = "返回条数上限，默认 10")
            @RequestParam(defaultValue = "10") Integer size,
            HttpServletRequest httpRequest) {
        limit(RateLimitKeys.DIM_SUGGEST_IP, properties.getRateLimit().getSuggest(), httpRequest);
        return R.ok(suggestService.suggest(typeCode, prefix, size, LoginUser.currentUserId()));
    }

    /** 热词榜。匿名可用，按 IP 限流。仅返回词条文本列表。 */
    @Operation(summary = "热词榜", description = """
            匿名可用，按 IP 限流。返回纯词条文本列表。

            榜上的词由检索行为累计而来，**可以被刷**。限流与去重只能抬高刷榜成本，
            挡不住分布式刷量 —— 热词榜需要人工巡检，不要把它当成可信数据。""")
    @GetMapping("/hot")
    public R<List<String>> hot(
            @Parameter(description = "类型编码，取值见 GET /api/search/types", example = "article")
            @RequestParam String typeCode,
            @Parameter(description = "返回条数上限，默认 10")
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest httpRequest) {
        limit(RateLimitKeys.DIM_HOT_IP, properties.getRateLimit().getHot(), httpRequest);
        return R.ok(hotWordService.topHotWords(typeCode, size));
    }

    /**
     * 可检索类型列表。匿名可用，按 IP 限流。供搜索页的类型选择器使用。
     *
     * <h3>为什么要单开这个接口，而不是让前端读管理后台的 {@code /api/admin/index-type}</h3>
     * 后者的响应体里带着 {@code field_config} 与 {@code search_config} ——
     * 索引的完整 mapping 与打分配置。而选择器需要的只有「编码 + 名字」。
     * 复用管理接口等于为了一个下拉框把索引结构公开出去，两者相差一个数量级的信息量。
     * 这里返回 {@link SearchTypeDTO}（只有两个字段），
     * 让"不会泄漏内部配置"由<b>返回类型</b>保证，而不是靠后人记得别加字段。
     *
     * <p>不含 {@code webpage}：采集内容有独立的 {@link #webSearch} 入口，
     * 在结果页与站内结果是并列的两个分组。放进站内选择器只会让用户选中它之后
     * 看到站内那一组空着 —— 见 {@code IndexTypeConfigServiceImpl} 里的常量说明。
     *
     * <p>返回空列表是合法状态（还没配任何类型），不是错误：前端据此提示
     * "暂无可选类型"，而不是当成接口故障。
     */
    @Operation(summary = "可检索类型列表", description = """
            匿名可用，按 IP 限流。搜索页类型选择器的数据源，只返回 `typeCode` + `typeName`。

            两个容易踩的点：

            - **返回空数组是合法状态**（还没配任何启用的类型），不是接口故障，前端应据此提示而不是报错。
            - **不含 `webpage`**：采集内容有独立的检索入口，出现在站内选择器里只会让人选中它之后
              看到空结果。""")
    @GetMapping("/types")
    public R<List<SearchTypeDTO>> types(HttpServletRequest httpRequest) {
        limit(RateLimitKeys.DIM_TYPES_IP, properties.getRateLimit().getTypes(), httpRequest);
        return R.ok(indexTypeConfigService.listSearchableTypes());
    }

    /**
     * 当前用户的搜索历史。需 {@code search:read}，<b>刻意不放行匿名访问</b>。
     *
     * <p>未登录时前端不应调用它 —— 未登录的历史存在浏览器本地
     * （见 {@code frontend/src/utils/localHistory.ts}），而不是这里。
     *
     * <p>坑：必须取当前登录用户，绝不能接受请求传入的 userId，否则会水平越权读到他人历史。
     */
    @Operation(summary = "我的搜索历史", description = """
            **需要登录**（`search:read`）。这是本组唯一的需要登录的端点。

            未登录时前端不应调用它 —— 未登录的历史存在浏览器本地，
            由 `frontend/src/utils/localHistory.ts` 维护，登录后可经
            `POST /api/auth/history/import` 一次性导入。""")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasAuthority('search:read')")
    @GetMapping("/history")
    public R<List<String>> history(
            @Parameter(description = "类型编码，取值见 GET /api/search/types", example = "article")
            @RequestParam String typeCode,
            @Parameter(description = "返回条数上限，默认 10")
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(hotWordService.userHistory(requireUserId(), typeCode, size));
    }

    /**
     * 清空当前用户在某类型下的搜索历史。需 {@code search:read}。
     *
     * <p>坑：同样只能清自己的历史，不受理外部传入的 userId。
     */
    @Operation(summary = "清空我的搜索历史", description = """
            **需要登录**（`search:read`）。只清当前令牌所属用户在该类型下的历史。""")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasAuthority('search:read')")
    @DeleteMapping("/history")
    public R<Void> clearHistory(
            @Parameter(description = "类型编码，取值见 GET /api/search/types", example = "article")
            @RequestParam String typeCode) {
        hotWordService.clearHistory(requireUserId(), typeCode);
        return R.ok();
    }

    private Long requireUserId() {
        Long userId = LoginUser.currentUserId();
        if (userId == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 执行一次限流检查。
     *
     * <p>抽成方法是为了保证「每个公开端点都限流」这件事在本文件里可以被一眼核对：
     * 数一数公开端点有几个、{@code limit(} 出现几次即可。
     * 逐个内联的话，漏掉一个不会有任何编译或运行时提示。
     *
     * <p>阈值取的是 {@code Limit#ipLimit}；{@code limit <= 0} 表示该维度不限流
     * （压测时用），由 {@link RateLimitGuard} 内部处理。
     */
    private void limit(String dimension, KumaSearchProperties.RateLimit.Limit limit,
                       HttpServletRequest request) {
        rateLimitGuard.check(dimension, clientIp(request),
                limit.getIpLimit(),
                limit.effectiveWindowSeconds(properties.getRateLimit().getWindowSeconds()));
    }

    /**
     * 取客户端 IP，<b>只认 socket 地址，不读任何请求头</b>。
     *
     * <p><b>为什么必须这样。</b>先前的版本按 {@code X-Forwarded-For → X-Real-IP → RemoteAddr}
     * 的顺序取值，那在「直接对外暴露的应用」上是必要的，但本应用前置了 Nginx，
     * 而这个顺序有一个致命前提：<b>请求头是客户端可以随便写的</b>。
     * 一个脚本每次带一个不同的 {@code X-Forwarded-For}，就能让基于它的限流形同不存在 ——
     * 每个请求都落在全新的桶里，而且写进 {@code t_search_log} 的 IP 与热词风控的
     * 判断依据也全是伪造值。
     *
     * <p>现在改成只读 {@code getRemoteAddr()}，由容器在<b>可信代理</b>转发时还原真实 IP：
     * Nginx 发来的 {@code X-Forwarded-For} 会被 Tomcat 的 RemoteIpValve 接受并改写
     * {@code getRemoteAddr()} 的返回值；而来自其它来源的 XFF 一律忽略。
     * 决定"谁说的话可信"的是 {@code server.tomcat.remoteip.internal-proxies}，
     * 见 {@code docs/07-deployment.md} §五 5.4 —— <b>那一项漏配等于本方法退化成读 XFF</b>，
     * 两个改动必须成对上线。
     *
     * <p>同样的取法见 {@code LoginAttemptGuard}（它一开始就是对的），
     * 两处行为一致，运维排查时不需要区分"哪个接口的 IP 是真的"。
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
