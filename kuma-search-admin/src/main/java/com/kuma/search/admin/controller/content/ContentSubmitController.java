package com.kuma.search.admin.controller.content;

import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.admin.security.RateLimitGuard;
import com.kuma.search.admin.security.RateLimitKeys;
import com.kuma.search.admin.service.ContentSubmitService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.dto.resp.ContentTypeSchemaDTO;
import com.kuma.search.repository.entity.SearchContent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台用户投稿接口。整个控制器需要权限码 {@code content:submit}。
 *
 * <h3>它和 {@code /api/admin/content} 不是同一件事，别合并</h3>
 * 两条路写的是同一张表，但契约相反：管理侧<b>信任</b>操作者（可设状态、可改作者、可调权重），
 * 本控制器面对的是<b>任何登录用户</b>，因此 {@code status} / {@code authorId} / {@code bizWeight}
 * 一律由服务端接管、{@code extra} 只允许写该类型声明过的字段。三条规则的推理写在
 * {@link ContentSubmitService} 的类注释里 —— 那才是这个控制器的实现，这里只负责收参数。
 *
 * <h3>为什么整套接口不在 {@code SecurityConfig} 的放行清单里</h3>
 * 它需要登录。放行清单里每一个端点都是"确实必须匿名可达"的，而投稿不是 ——
 * 匿名投稿意味着"谁写的"这个问题永远答不出来，审核队列也就失去了追责对象。
 * 因此这里靠兜底的 {@code authenticated()} + 类上的权限码两道，
 * 与 {@code /api/admin/**} 同一套（区别只是权限码不同）。
 *
 * <h3>限流为什么取消不掉</h3>
 * 一个登录用户就能调用的写接口，每次调用都会往 ES 里写一份文档。
 * 没有限流时，一个脚本能在几秒内把索引塞满垃圾，而清理它们需要人工逐条删除 ——
 * 这个代价由运营承担。因此 {@code submit / update} 两个入口都先过
 * {@link RateLimitGuard}，且是<b>用户与 IP 两个维度</b>（理由见
 * {@code RateLimitKeys.DIM_CONTENT_SUBMIT_USER}）。
 * 撤回接口刻意<b>不限流</b>：它是"减少"动作，一个用户反复撤回自己的稿子不构成滥用，
 * 而给它限流会让"发现投错了想赶紧撤掉"的正常操作被拦住。
 */
@Slf4j
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('content:submit')")
@Tag(name = "前台投稿", description = """
        登录用户投稿站内内容、查看与撤回自己的投稿。

        需要权限码 `content:submit`（USER 角色与管理员都有）。

        **投出去的稿子一律落"待审"状态，审核通过后才搜得到** ——
        这不是"写入失败"，接口返回的 id 是有效的。

        写入走与后台相同的 ContentService（同事务写 Outbox），ES 侧秒级异步可见。
        提交与修改按用户与 IP 两个维度限流，超限返回 `10003` / HTTP 429。
        """)
@SecurityRequirement(name = "bearerAuth")
public class ContentSubmitController {

    private final ContentSubmitService contentSubmitService;
    private final RateLimitGuard rateLimitGuard;
    private final KumaSearchProperties properties;

    /**
     * 可投稿的类型结构。
     *
     * <p>不限流：它的数据源是索引类型配置（一个几行的表，且有缓存），
     * 而前端每次打开投稿页都要用它渲染表单 —— 给它限流的结果是"页面打开时表单空白"。
     */
    @GetMapping("/schema")
    @Operation(summary = "可投稿的类型与字段结构", description = """
            只返回**已启用且「允许前台投稿」为真**的类型。返回里同时带着
            `serverManagedFields`（由服务端接管、表单不该渲染的字段名）与
            每个字段的 `inExtra`（该填到请求体顶层还是 `extra` 里），前端按这两个标记渲染即可，
            不要在前端再维护一份字段名单。""")
    public R<List<ContentTypeSchemaDTO>> schema() {
        return R.ok(contentSubmitService.listSubmittableSchemas());
    }

    /** 我的投稿。 */
    @GetMapping("/my")
    @Operation(summary = "我的投稿", description = """
            只返回当前登录用户自己的投稿，`authorId` 由服务端强制，不接受传参。
            含全部状态（0 待审 / 1 已发布 / 2 已驳回），因此列表里会同时出现已发布与待审的稿子。
            被驳回的稿子，驳回原因在 `extra.rejectReason`，命中的敏感词在 `extra.matchedWords`
            —— 这两个键没有在字段配置里声明，所以只留在数据库里、不会进索引。""")
    public R<PageResult<SearchContent>> my(
            @Parameter(description = "索引类型编码；省略则返回全部类型", example = "article")
            @RequestParam(required = false) String typeCode,
            @Parameter(description = "页码，从 1 开始；默认 1", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数；默认 10", example = "10")
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(contentSubmitService.mySubmissions(requireUserId(), typeCode, pageNum, pageSize));
    }

    /**
     * 新增投稿。
     *
     * @return 记录主键
     */
    @PostMapping("/submit")
    @Operation(summary = "新增投稿", description = """
            请求体是内容对象（与后台内容接口同形），其中 `bizId` 会被服务端覆盖生成，
            `status` / `authorId` / `bizWeight` 由服务端接管，请求里带了也不作数。

            `title` 必填；`extra` 里只允许出现该类型字段配置声明过的键，否则返回 `30001`。

            正文命中敏感词库时，稿件直接落"已驳回"（仍返回 200 与主键）——
            这是一次**成功的写入**，不是失败，投稿人会在「我的投稿」里看到驳回原因。""")
    public R<Long> submit(@RequestBody SearchContent content, HttpServletRequest httpRequest) {
        Long userId = requireUserId();
        limitSubmit(userId, httpRequest);
        return R.ok(contentSubmitService.submit(userId, content));
    }

    /** 修改自己的投稿。 */
    @PutMapping("/submit")
    @Operation(summary = "修改投稿", description = """
            只能改自己的、且**尚未发布**的投稿（状态为待审或已驳回）。

            已发布的投稿会被拒绝（`20006`）—— 否则「先投一篇正常的过审、再改成任意内容」
            就能绕过整个审核队列。需要改动请联系管理员先下架。

            不是自己的稿子返回 `43001`（而不是 403）：403 等于承认"这个 bizId 存在，只是不归你"，
            可以拿来枚举他人内容。

            修改成功后稿件回到"待审" —— 改过的就是新的一版，要重新审。""")
    public R<Void> update(@RequestBody SearchContent content, HttpServletRequest httpRequest) {
        Long userId = requireUserId();
        limitSubmit(userId, httpRequest);
        contentSubmitService.update(userId, content);
        return R.ok();
    }

    /** 撤回自己的投稿。 */
    @DeleteMapping("/my/{typeCode}/{bizId}")
    @Operation(summary = "撤回投稿", description = """
            逻辑删除 MySQL 行并投递索引删除事件，ES 侧约 1 秒后不再可搜。

            与「修改」同一条约束：已发布（status=1）的投稿不能自行撤回，会返回 `20006`。
            撤回后可以用同一个标题再投一次（会生成新的 bizId），
            但**不会**恢复成原来那条 —— 原记录的删除是审计痕迹，不该被覆盖。""")
    public R<Void> withdraw(
            @Parameter(description = "索引类型编码", example = "article")
            @PathVariable String typeCode,
            @Parameter(description = "业务主键，投稿时由服务端生成并返回", example = "s-3f2a...")
            @PathVariable String bizId) {
        contentSubmitService.withdraw(requireUserId(), typeCode, bizId);
        return R.ok();
    }

    // ==================================================================================
    //  内部
    // ==================================================================================

    /** 用户与 IP 两个维度各计一次，任一超限即抛 {@code RATE_LIMITED}。 */
    private void limitSubmit(Long userId, HttpServletRequest request) {
        KumaSearchProperties.RateLimit.Limit limit = properties.getRateLimit().getContentSubmit();
        int window = limit.effectiveWindowSeconds(properties.getRateLimit().getWindowSeconds());
        rateLimitGuard.check(RateLimitKeys.DIM_CONTENT_SUBMIT_USER,
                String.valueOf(userId), limit.getUserLimit(), window);
        rateLimitGuard.check(RateLimitKeys.DIM_CONTENT_SUBMIT_IP,
                request.getRemoteAddr(), limit.getIpLimit(), window);
    }

    /**
     * 取当前用户 ID，取不到就中断。
     *
     * <p>与 {@code SearchController} / {@code AuthController} 里那两个同名方法是同一段代码，
     * 同样<b>刻意不抽成公共方法</b>：{@code LoginUser} 已经是"当前用户"的工具类，
     * 再包一层只会让"用户 ID 从哪来"多一个跳转。三行代码，比一个所有人绕着走的抽象便宜。
     *
     * <p>即使类上已经有 {@code @PreAuthorize}（未登录根本进不来）也保留这个判断：
     * 权限注解是配置，配置会被后来的人改宽（比如为了内部调用加个 {@code permitAll}），
     * 而"拿不到用户就绝不继续写数据"这条约束必须由代码自己保证 ——
     * 这条链路的每一个写操作都以 userId 为归属依据，为空时写进去的就是一条无主内容。
     */
    private Long requireUserId() {
        Long userId = LoginUser.currentUserId();
        if (userId == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }
}
