package com.kuma.search.admin.controller.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kuma.search.admin.dto.AuditActionRequest;
import com.kuma.search.admin.dto.AuditBatchAction;
import com.kuma.search.admin.dto.BatchAuditRequest;
import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.R;
import com.kuma.search.crawl.CrawlAuditService;
import com.kuma.search.dto.resp.BatchAuditResultDTO;
import com.kuma.search.repository.entity.CrawlAuditLog;
import com.kuma.search.repository.entity.SearchContent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集内容的人工审核接口。整个控制器需要权限码 {@code admin:content:manage}。
 *
 * <p><b>权限码复用而不新增。</b>审核网页采集内容与审核站内内容在语义上是同一件事
 * （"能不能把这条内容对外展示"），拆成两个权限码只会让"谁该有审核权"这个问题
 * 出现两个答案，而它们的答案总是相同的。权限码一旦新增就要同步
 * {@code sql/data.sql}、各 Controller 注解与权限矩阵文档，成本远大于收益。
 *
 * <h3>⚠️ 这个审核队列是法定义务的落点，不是可选功能</h3>
 * 域名已备案、对外公开提供服务，采集内容在发布前必须经过审核。
 * 三种动作的区别（放行 / 驳回 / 撤回）见 {@link CrawlAuditService} 的类注释 ——
 * 特别是 {@code REJECT} 与 {@code TAKEDOWN} 不能合并：
 * 「这条内容曾经对外可见过多久」在投诉与法务场景下是必须能回答的问题。
 *
 * <h3>写入是异步的</h3>
 * 放行只是把状态改为 1 并写出 Outbox 事件，ES 侧是秒级异步可见。
 * 因此"点了放行但搜不到"在几秒内是正常的，接口返回不代表已可检索。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/crawl/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:content:manage')")
@Tag(name = "采集审核", description = """
        采集内容（type_code=webpage）的人工审核队列，是法定义务的落点，不是可选功能。

        待审 / 已驳回是 t_search_content 中 type_code='webpage' 且 status 为 0 / 2 的行。
        **采集内容必须经放行（status 置 1）才会被检索到**，未审核的搜不到。

        放行 / 驳回走既有写链路写出 Outbox，ES 秒级异步可见，接口返回不代表已可检索。
        整个控制器需要权限码 `admin:content:manage`（刻意复用，不新增权限码）。""")
@SecurityRequirement(name = "bearerAuth")
public class CrawlAuditController {

    private final CrawlAuditService crawlAuditService;

    /**
     * 待审 / 已驳回列表，分页。
     *
     * @param status       0 待审 / 2 已驳回；不传表示两者都要
     * @param domain       按来源域名筛选
     * @param matchedWords 按命中的敏感词筛选
     */
    @GetMapping
    @Operation(summary = "列出待审核内容", description = """
            只列采集内容（type_code=webpage）里 status 为 0（待审）或 2（已驳回）的行，
            不含已发布（status=1）—— 后者属于线上内容管理，不是审核队列。
            status 省略表示待审与已驳回都要。domain 与 matchedWords 按 extra JSON 里的字段模糊匹配。""")
    public R<PageResult<SearchContent>> page(
            @Parameter(description = "页码，从 1 开始；默认 1", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数；默认 10", example = "10")
            @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "0 待审 / 2 已驳回；省略表示两者都要", example = "0")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "来源域名，匹配 extra 中的 domain")
            @RequestParam(required = false) String domain,
            @Parameter(description = "命中的敏感词，匹配 extra 中的 matchedWords")
            @RequestParam(required = false) String matchedWords) {
        return R.ok(toPageResult(
                crawlAuditService.listPending(pageNum, pageSize, status, domain, matchedWords),
                pageNum, pageSize));
    }

    /** 待审数量，供后台角标。 */
    @GetMapping("/pending-count")
    @Operation(summary = "待审数量", description = """
            只统计 type_code=webpage 且 status=0 的条数，供后台角标显示。""")
    public R<Long> pendingCount() {
        return R.ok(crawlAuditService.countPending());
    }

    /**
     * 放行：置为已发布，走既有写链路进 ES。
     *
     * <p>坑：这条路径<b>只能从待审（0）走</b>。对已发布的内容重复放行不会报错，
     * 但会写出第二条 APPROVE 日志，让"这条内容被审过几次"变得不可读。
     */
    @PostMapping("/{contentId}/approve")
    @Operation(summary = "放行", description = """
            置 status=1 并走既有写链路（ContentService.save）写出 Outbox，ES 秒级异步可见 ——
            接口返回不代表已可检索，点完立刻搜不到在几秒内是正常的。
            这条路径只应从待审（0）出发；对已发布内容重复放行不报错，但会多写一条 APPROVE 日志。
            请求体可整个省略，理由非必填。""")
    public R<Void> approve(
            @Parameter(description = "内容主键（t_search_content.id）")
            @PathVariable Long contentId,
            @RequestBody(required = false) AuditActionRequest body) {
        crawlAuditService.approve(contentId, currentOperator(), reasonOf(body));
        return R.ok();
    }

    /** 驳回：置为已驳回，不进 ES（已发布过的会同步从检索中消失）。 */
    @PostMapping("/{contentId}/reject")
    @Operation(summary = "驳回下架", description = """
            置 status=2，不进 ES；若该内容此前已发布过，会随之从检索结果中消失
            （查询侧强制 status=1 过滤）。该动作可逆，再放行一次即可恢复。
            请求体可整个省略，理由非必填。""")
    public R<Void> reject(
            @Parameter(description = "内容主键（t_search_content.id）")
            @PathVariable Long contentId,
            @Valid @RequestBody(required = false) AuditActionRequest body) {
        crawlAuditService.reject(contentId, currentOperator(), reasonOf(body));
        return R.ok();
    }

    /**
     * 撤回下架：从 ES 中移除。
     *
     * <p><b>这是三个动作里唯一作用于"已经对外可见过"的内容的，因此最需要人工确认。</b>
     * 前端必须弹二次确认，且提示语要说清"内容会从搜索结果中消失"，
     * 而不是笼统的"确定要删除吗"。
     */
    @PostMapping("/{contentId}/takedown")
    @Operation(summary = "撤回已发布", description = """
            从 ES 中移除并逻辑删除内容行，是三个动作里唯一作用于「已经对外可见过」的内容的。
            内容会从搜索结果中消失，且不可逆（这点与驳回不同）。前端应弹二次确认。
            请求体可整个省略，理由非必填。""")
    public R<Void> takedown(
            @Parameter(description = "内容主键（t_search_content.id）")
            @PathVariable Long contentId,
            @Valid @RequestBody(required = false) AuditActionRequest body) {
        crawlAuditService.takedown(contentId, currentOperator(), reasonOf(body));
        return R.ok();
    }

    /**
     * 批量放行 / 驳回。
     *
     * <p>存在这个接口的原因是效率与积压：采集是持续的（爬虫跑着就有新内容进来），
     * 人审是间歇的，这个速度差必然产生积压。<b>积压的后果不是"麻烦"，而是内容长期不可检索</b>
     * —— 采集这件事等于白做。逐条点放行的队列，第一次上线时就有几百条要清。
     *
     * <p>它与单条接口的关系是"同一件事做 N 次"，不是"另一件事"：
     * 每一条都仍然走 {@code CrawlAuditService#approve} / {@code reject}，
     * 因此 Outbox 事件、审核日志、ES 同步的行为与逐条点击完全一致。
     */
    @PostMapping("/batch")
    @Operation(summary = "批量放行 / 驳回", description = """
            对一批采集内容执行同一个动作（放行或驳回），用于清理积压。两种定位方式二选一：
            **contentIds**（处理勾中的这几条）或 **domain/matchedWords**（处理该筛选条件下的待审内容，最多 limit 条）。

            每一条都走与单条接口完全相同的写入链路（状态 + Outbox + 审核日志），因此
            行为一致、ES 同样秒级异步可见。**逐条独立事务**：其中一条失败不影响其余各条，
            失败明细在响应的 failures 里，其余四条计数满足 requested = succeeded + failed + skipped。

            ⚠️ 三条必须知道的：
            1. **不支持撤回（TAKEDOWN）**。批量只做放行与驳回 —— 撤回不可逆且作用于已对外可见的内容，
               与"驳回"在界面上只差一个字，后果却差一个数量级，因此始终逐条带二次确认。
            2. **按条件模式只作用于待审（status=0）**，不会碰到已发布的内容。
            3. **调用方需要自己判断要不要再来一次**：按条件模式的条数有上限（默认 200，最大 2000），
               响应里的 remainingPending 是处理完后剩余的待审总数，不为 0 就再调一次。

            skipped 不是错误：状态已经是目标状态（例如别人刚放行过）或不是采集内容时会跳过，
            重试没有意义。""")
    public R<BatchAuditResultDTO> batch(@Valid @RequestBody BatchAuditRequest body) {
        // 未知动作在这里直接报参数错误，绝不回落到某个默认动作 ——
        // 批量放行是不可逆的对外动作，一次拼写错误不该以"几百条内容被发布"收场。
        AuditBatchAction action = AuditBatchAction.parse(body.getAction());

        String operator = currentOperator();
        String reason = body.getReason();
        BatchAuditResultDTO result = action == AuditBatchAction.APPROVE
                ? crawlAuditService.batchApprove(body.getContentIds(), body.getDomain(),
                        body.getMatchedWords(), body.getLimit(), operator, reason)
                : crawlAuditService.batchReject(body.getContentIds(), body.getDomain(),
                        body.getMatchedWords(), body.getLimit(), operator, reason);

        log.info("批量{}已受理：请求={}，成功={}，失败={}，跳过={}，剩余待审={}，operator={}",
                action.label(), result.getRequested(), result.getSucceeded(),
                result.getFailed(), result.getSkipped(), result.getRemainingPending(), operator);
        return R.ok(result);
    }

    /**
     * 某条 URL 的审核历史。
     *
     * <p>按 {@code urlHash} 而不是 {@code contentId} 查：内容被撤回后
     * {@code contentId} 会查不到东西，而 urlHash 永远可重算。
     */
    @GetMapping("/logs")
    @Operation(summary = "列出审核日志", description = """
            按 urlHash 而不是 contentId 查：内容被撤回后 contentId 会查不到，而 urlHash 永远可重算。
            urlHash 省略则返回全部日志。""")
    public R<PageResult<CrawlAuditLog>> logs(
            @Parameter(description = "页码，从 1 开始；默认 1", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数；默认 20", example = "20")
            @RequestParam(defaultValue = "20") int pageSize,
            @Parameter(description = "URL 哈希；省略则返回全部")
            @RequestParam(required = false) String urlHash) {
        return R.ok(toPageResult(
                crawlAuditService.listLogs(pageNum, pageSize, urlHash), pageNum, pageSize));
    }

    /**
     * {@code Page} → {@code PageResult}。
     *
     * <p>项目里这个转换通常发生在 service 层（见 {@code ContentAdminService}），
     * 这里放在控制器内是因为 {@code CrawlAuditService} 属于 core 模块，
     * <b>不能依赖</b> {@code PageResult} 之外的 admin 分页约定 ——
     * 它为两种调用方（后台接口与将来的定时复核任务）服务，
     * 把"接口响应长什么样"塞进它会让 core 反向承载 admin 的关注点。
     *
     * <p>抽成私有泛型方法而不是给 {@code PageResult} 加一个接收 MyBatis-Plus
     * {@code Page} 的工厂：那会让 common 模块依赖 MyBatis-Plus 的分页类型，
     * 而 common 目前只依赖 Jackson 与 Lombok。
     */
    private <T> PageResult<T> toPageResult(Page<T> page, int pageNum, int pageSize) {
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }

    /**
     * 当前操作者，用于审核留痕。
     *
     * <p>取不到用户时返回 null，由服务层回落到 {@code system} ——
     * 而不是抛异常：这条记录的价值在于"有人做过这个动作"，
     * 因为拿不到用户名就让整次审核失败是本末倒置。
     * <b>但正常路径下不该出现 null</b>：这里的接口需要认证，SecurityContext 必然有用户。
     */
    private String currentOperator() {
        LoginUser user = LoginUser.current();
        return user == null ? null : user.getUsername();
    }

    /** 请求体可以整个省略（{@code @RequestBody(required=false)}），因此取理由要先判空。 */
    private String reasonOf(AuditActionRequest body) {
        return body == null ? null : body.getReason();
    }
}
