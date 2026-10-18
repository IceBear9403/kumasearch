package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.service.ContentAdminService;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.R;
import com.kuma.search.dto.resp.ContentTypeSchemaDTO;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.service.IndexTypeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 内容管理接口。整个控制器需要权限码 {@code admin:content:manage}。
 *
 * <p>写入走 core 的 ContentService（同事务写 Outbox），因此接口返回只代表 MySQL 已落库，
 * ES 侧为秒级异步可见。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/content")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:content:manage')")
@Tag(name = "内容管理", description = """
        对检索内容表（t_search_content）的增删改查，覆盖所有内容类型，不只采集内容。

        写入（新建 / 删除 / 批量导入）走 core 的 ContentService，与 Outbox 事件同事务提交，
        ES 侧为秒级异步可见 —— 接口返回只代表 MySQL 已落库，立即检索可能还查不到。

        整个控制器需要权限码 `admin:content:manage`。""")
@SecurityRequirement(name = "bearerAuth")
public class ContentController {

    private final ContentAdminService contentAdminService;
    /** 只用来给内容表单提供字段结构（{@code GET /schema}）。类型的增删改走 {@code IndexTypeController}。 */
    private final IndexTypeConfigService indexTypeConfigService;

    /**
     * 后台手工录入内容时自动生成 bizId 的前缀。
     *
     * <p>{@code bizId} 是 ES 文档 _id 的来源，全站约定"由写入方提供"（采集侧写 url_hash）。
     * 后台表单允许留空，此时由<b>控制器</b>生成而不是塞进 {@code ContentService}：
     * 那个方法的契约是 bizId 必填（缺失会导致同一份内容被反复建成新文档），
     * 核心写入链路不该猜调用方的意图 —— 该不该自动生成是入口层的决定。
     *
     * <p>前缀刻意可读（{@code m-} = manual，投稿侧是 {@code s-}，采集侧是 url 的哈希）：
     * 库里出问题时，"这条内容是谁录进来的"不该需要翻日志才能回答。
     */
    private static final String ADMIN_BIZ_ID_PREFIX = "m-";

    /** 分页查询内容列表。可按 typeCode、标题关键字、状态、作者过滤。 */
    @GetMapping
    @Operation(summary = "分页查询内容", description = """
            typeCode、keyword、status、authorId 均可省略；keyword 只对标题做模糊匹配，不搜正文。
            省略 status 时**不过滤状态**，待审（0）与已驳回（2）的内容也会出现 ——
            内容维护页的「待审」筛选要显式传 `status=0`。页码从 1 开始。""")
    public R<PageResult<SearchContent>> page(
            @Parameter(description = "索引类型编码；省略则返回全部类型", example = "article")
            @RequestParam(required = false) String typeCode,
            @Parameter(description = "页码，从 1 开始；默认 1", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页条数；默认 10", example = "10")
            @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "标题关键字，模糊匹配；省略则不过滤")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "内容状态：1 已发布 / 0 待审 / 2 已驳回；省略则不过滤。"
                    + "⚠️ 传 `status=0` 与省略**不是一回事**，前者只返回待审内容")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "作者 ID，精确匹配；省略则不过滤")
            @RequestParam(required = false) String authorId) {
        return R.ok(contentAdminService.page(typeCode, pageNum, pageSize, keyword, status, authorId));
    }

    /**
     * 内容表单的字段结构。
     *
     * <p>与 {@code /api/search/types} 的区别：那个给搜索页的类型选择器用，只有 code + name；
     * 这个给内容录入表单用，带着 field_config 解析出的字段元数据。
     * 因此<b>不能</b>把它挪到公开的 {@code /api/search} 下。
     */
    @GetMapping("/schema")
    @Operation(summary = "内容表单的字段结构", description = """
            返回所有**已启用**类型及其字段元数据，供内容录入表单动态渲染。

            与 `/api/search/types` 不同：那个只给搜索页的类型选择器（code + name），
            这个带完整字段定义，因此需要 `admin:content:manage`。

            每个字段上的 `inExtra` 表明该填到请求体顶层还是 `extra` JSON 里，
            前端按它渲染即可，不要在前端另维护一份"哪些字段是固定列"的名单。""")
    public R<List<ContentTypeSchemaDTO>> schema() {
        return R.ok(indexTypeConfigService.listContentSchemas(false));
    }

    /** 按类型与业务主键查详情。 */
    @GetMapping("/{typeCode}/{bizId}")
    @Operation(summary = "查询内容详情", description = """
            以 typeCode + bizId 联合定位，bizId 是业务系统原始主键，不是自增 id。
            内容不存在时返回业务错误（CONTENT_NOT_FOUND）。""")
    public R<SearchContent> detail(
            @Parameter(description = "索引类型编码", example = "article")
            @PathVariable String typeCode,
            @Parameter(description = "业务系统原始主键（非自增 id）")
            @PathVariable String bizId) {
        return R.ok(contentAdminService.get(typeCode, bizId));
    }

    /**
     * 新增或更新内容（幂等，以 typeCode+bizId 为准）。
     *
     * @return 记录主键
     */
    @PostMapping
    @Operation(summary = "新建内容", description = """
            幂等 upsert：以 typeCode + bizId 为准，存在则更新、不存在则新增。typeCode、title 不能为空；
            **bizId 可以留空，留空时由服务端生成**（形如 `m-<32位十六进制>`），响应里返回的是主键 id，
            要拿 bizId 请调详情接口。未带 status 时默认置为 1（已发布）。

            待审内容的「放行 / 驳回」也走这个接口：先把详情取出来、改 `status` 再整体提交
            （status 1 = 放行，2 = 驳回），没有单独的审核端点。

            写入与 Outbox 事件同事务提交，ES 秒级异步可见 —— 返回只代表 MySQL 已落库，
            立即检索可能还查不到。""")
    public R<Long> save(@RequestBody SearchContent content) {
        if (!StringUtils.hasText(content.getBizId())) {
            // 只有真的留空时才生成。已有 bizId 的调用（编辑、放行、外部系统同步）
            // 必须原样保留 —— 覆盖它等于把一条内容改成另一条，而调用方拿到的是同一个 id。
            content.setBizId(ADMIN_BIZ_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        }
        return R.ok(contentAdminService.save(content));
    }

    /** 删除内容。坑：删除同样走 Outbox 异步同步到 ES，不是立即从索引消失。 */
    @DeleteMapping("/{typeCode}/{bizId}")
    @Operation(summary = "删除内容", description = """
            逻辑删除 MySQL 行并写出 Outbox DELETE 事件；ES 侧的删除是异步的，约 1 秒后才从索引消失，
            不是立即生效。删除不存在的记录按幂等成功处理。""")
    public R<Void> delete(
            @Parameter(description = "索引类型编码", example = "article")
            @PathVariable String typeCode,
            @Parameter(description = "业务系统原始主键（非自增 id）")
            @PathVariable String bizId) {
        contentAdminService.delete(typeCode, bizId);
        return R.ok();
    }

    /**
     * 批量导入内容。
     *
     * @return 成功保存的条数
     */
    @PostMapping("/batch")
    @Operation(summary = "批量导入内容", description = """
            按 500 条分块、每块一个独立事务；单条非法数据会被跳过而不让整批失败，
            返回实际成功写入的条数（可能小于入参条数）。与单条写入一样，ES 为秒级异步可见。""")
    public R<Integer> batch(@RequestBody List<SearchContent> contents) {
        return R.ok(contentAdminService.batchSave(contents));
    }
}
