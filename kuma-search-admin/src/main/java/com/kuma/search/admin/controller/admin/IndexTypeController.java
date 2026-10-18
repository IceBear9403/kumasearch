package com.kuma.search.admin.controller.admin;

import com.kuma.search.admin.dto.FieldConfigValidateRequest;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.repository.entity.IndexTypeConfig;
import com.kuma.search.service.ContentService;
import com.kuma.search.service.DynamicIndexManager;
import com.kuma.search.service.IndexTypeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 索引类型配置与索引生命周期管理接口。整个控制器需要权限码 {@code admin:index:manage}。
 *
 * <p>域内高危操作：删除索引、全量重建、重载词典，均为重操作，务必确认目标 typeCode。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/index-type")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('admin:index:manage')")
@Tag(name = "类型配置与索引", description = """
        索引类型配置的增删改查，以及单个类型的索引生命周期操作
        （首次创建、全量重建、重建联想索引、重载分词器、删除索引、状态查看与 mapping 预览）。

        **本组全部端点需要权限码 `admin:index:manage`。**
        写操作（建/重建/删除索引、重载分词器）会影响线上检索，执行前务必确认目标 typeCode。

        **首次建索引必须按顺序**：索引从未创建过时先调 `create-index`，之后才能调 `rebuild`。
        `rebuild` 会按当前版本号推导出「旧索引」并对其做别名摘除，索引不存在时推导出的旧索引同样不存在，会直接失败。
        """)
@SecurityRequirement(name = "bearerAuth")
public class IndexTypeController {

    private final IndexTypeConfigService indexTypeConfigService;
    private final DynamicIndexManager dynamicIndexManager;
    /** 只用于「删除配置前确认该类型下没有内容」这一条前置检查。 */
    private final ContentService contentService;

    /** 列出全部类型配置（含停用的）。 */
    @GetMapping
    @Operation(summary = "列出类型配置", description = """
            返回全部类型配置（含已停用的），按 id 升序。
            停用只影响检索链路（该类型会被当作不存在），不会让它从这个列表里消失。""")
    public R<List<IndexTypeConfig>> list() {
        return R.ok(indexTypeConfigService.listAll());
    }

    /** 按类型编码查配置详情。 */
    @GetMapping("/{typeCode}")
    @Operation(summary = "按编码查配置", description = """
            按 typeCode 查询单个类型配置。
            已停用的类型会被当作「不存在」并返回业务错误（不是空对象），停用即时生效、无需等待缓存过期。""")
    public R<IndexTypeConfig> detail(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        return R.ok(indexTypeConfigService.getByTypeCode(typeCode));
    }

    /**
     * 新建类型配置。
     *
     * @return 新配置主键；坑：typeCode 必须全局唯一，重复会抛业务异常
     */
    @PostMapping
    @Operation(summary = "新建类型配置", description = """
            落库一条新的类型配置并返回主键 id，只写配置表，不会自动创建 ES 索引（需另行调用 create-index）。
            坑：typeCode 必须全局唯一，重复会抛业务异常；typeCode 会被拼进索引名，只允许小写字母、数字与下划线且以字母开头。""")
    public R<Long> create(@RequestBody IndexTypeConfig config) {
        return R.ok(indexTypeConfigService.create(config));
    }

    /**
     * 更新类型配置。
     *
     * <p>坑：修改字段类型不会自动生效，必须随后触发全量重建。
     */
    @PutMapping
    @Operation(summary = "更新类型配置", description = """
            按 id 更新，body 必须带 id。typeCode、indexAlias、currentVersion 会被服务端强制忽略（它们是索引身份的一部分，不允许改）。
            坑：仅修改字段类型不会自动生效，必须随后触发全量重建（rebuild）才会反映到索引上。""")
    public R<Void> update(@RequestBody IndexTypeConfig config) {
        indexTypeConfigService.update(config);
        return R.ok();
    }

    /**
     * 删除类型配置（逻辑删除）。
     *
     * <p>两条前置检查必须都过，<b>顺序也不能反</b>（见 {@link #guardDeletable}）：
     * 索引已删掉、且该类型下没有未删除的内容。删配置不会顺带删索引与内容。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除类型配置", description = """
            按主键 id 逻辑删除类型配置。**不会**顺带删除 ES 索引与该类型下的内容，这两件事要按顺序先做：

            1. 先调「删除索引」—— 配置一旦被删，这个类型就被当作不存在，
               索引从此再也删不掉了（删除动作内部要先解析类型定义，那一步会直接失败），
               只能去 ES 里手工删；
            2. 再确认该类型下没有未删除的内容。

            两条任一不满足都返回 `10002` 并说明原因，**没有强制删除的开关**：
            清空一个大类型本就该先删索引、再走运维渠道，不做这种半自动的越权批量动作。""")
    public R<Void> delete(
            @Parameter(description = "类型配置主键 id", example = "1") @PathVariable Long id) {
        guardDeletable(findConfigById(id));
        indexTypeConfigService.delete(id);
        return R.ok();
    }

    /** 首次建索引：为主索引与（必要的）联想索引建 mapping 并绑定别名。 */
    @PostMapping("/{typeCode}/create-index")
    @Operation(summary = "创建索引", description = """
            为从未创建过索引的类型首次创建主索引与联想索引，绑定别名，并**给联想索引灌一次建议词**。
            联想索引只在 field_config 里有字段声明了 suggest=true 时才建；没有这种字段的类型不会建，
            这是配置表达的正常意图，不是失败。
            与 rebuild 是两个不同的动作，顺序不能反：本端点要求物理索引当前不存在（已存在会直接报错并提示改用 rebuild）；
            只在索引从未创建过时调用本端点，之后才能调用 rebuild。

            ⚠️ 「建索引」与「灌词」是两个可能分别失败的步骤：灌词失败时索引结构是好的、也能读，
            只是里面没有词 —— 现象是"联想不出来但索引明明在"。这时**不要重建索引**，
            再调一次 rebuild-suggest 或等定时全量（默认 6 小时）即可。""")
    public R<Void> createIndex(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        dynamicIndexManager.createIndex(typeCode);
        return R.ok();
    }

    /**
     * 全量重建索引（蓝绿切换，线上不中断）。
     *
     * <p>坑：重建过程含分布式锁 + 灌数据 + 校验 + 别名切换，耗时可能很长，不要在前端做同步等待式超时。
     */
    @PostMapping("/{typeCode}/rebuild")
    @Operation(summary = "重建索引", description = """
            全量重建：新建新版本物理索引 → 从 MySQL 全量灌数据 → 校验文档数 → 切别名 → 清理旧索引，蓝绿切换、线上不中断。
            同步执行：接口会一直阻塞到重建完成才返回，耗时随数据量增长（10 万级文档可达几十秒），前端不要按普通请求设超时。
            同一类型同时只允许一个重建（Redis 分布式锁），重复触发会报「正在重建中」。
            前置条件：该类型的索引必须已存在，首次请先调 create-index —— 本端点按当前版本号推导旧索引并对其摘除别名，旧索引不存在会直接失败。""")
    public R<Void> rebuild(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        dynamicIndexManager.rebuildIndex(typeCode);
        return R.ok();
    }

    /** 重建联想（completion）索引。 */
    @PostMapping("/{typeCode}/rebuild-suggest")
    @Operation(summary = "重建联想索引", description = """
            删除并重建该类型的联想（completion）索引，并**重新灌一次建议词**。
            对 field_config 里没有 suggest=true 字段的类型是空操作：那种类型本就不该有联想索引，
            给它建一个只会多出一个永远没人查的索引，所以接口什么都不做并打一条 WARN。
            联想索引缺失时（no such index [kuma_xxx_suggest]）也用本端点修复。

            为什么是"重建"而不是"增量补齐"：分析器与 max_input_length 都在索引的 settings 里，
            ES 不允许修改已有索引的 settings，只有重建才能让灌词规则的变化生效。
            代价是重建期间联想为空（通常几秒到几十秒，取决于内容条数）。

            ⚠️ 联想为空时先分清是缺索引还是缺词：
            索引不存在 → 用本端点；**索引在、里面没词（判定方式见 09-maintenance.md §4.8）→ 也用本端点**，
            但要知道它只灌已发布（status=1）的内容，源表没有已发布内容时它就该是空的。""")
    public R<Void> rebuildSuggest(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        dynamicIndexManager.rebuildSuggestIndex(typeCode);
        return R.ok();
    }

    /** 重载词典/分析器（词典编辑后需调用才生效）。 */
    @PostMapping("/{typeCode}/reload-analyzers")
    @Operation(summary = "重载分词器", description = """
            重新加载同义词/停用词等 search analyzer 配置。
            坑：只对之后写入的文档生效，已索引的历史文档不会重新分析；要让词典变更对存量数据生效，必须重建索引（rebuild）。""")
    public R<Void> reloadAnalyzers(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        dynamicIndexManager.reloadAnalyzers(typeCode);
        return R.ok();
    }

    /**
     * 删除索引（危险操作：会导致该类型检索立即不可用，且数据需靠重建恢复）。
     */
    @DeleteMapping("/{typeCode}/index")
    @Operation(summary = "删除索引", description = """
            删除该类型的主索引与联想索引，并清空其检索缓存。
            危险操作：会导致该类型检索立即不可用，数据只能靠重建恢复。""")
    public R<Void> deleteIndex(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        log.warn("删除索引索引 typeCode={}", typeCode);
        dynamicIndexManager.deleteIndex(typeCode);
        return R.ok();
    }

    /** 预览该类型将生成的 mapping，用于保存配置前核对。 */
    @GetMapping("/{typeCode}/mapping-preview")
    @Operation(summary = "预览 mapping", description = """
            返回该类型当前配置将生成的 mapping JSON，供保存配置前核对。
            只读操作，不落库、不改动任何索引。""")
    public R<String> mappingPreview(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        return R.ok(dynamicIndexManager.previewMapping(typeCode));
    }

    /** 查询索引运行状态（是否存在、文档数等）。 */
    @GetMapping("/{typeCode}/status")
    @Operation(summary = "查看索引状态", description = """
            返回索引是否就绪、别名、物理索引名、联想索引名、当前版本、文档数与源表行数。
            文档数与源表行数可用于核对索引是否缺数据。只读操作。""")
    public R<Map<String, Object>> status(
            @Parameter(description = "类型编码，如 article", example = "article") @PathVariable String typeCode) {
        return R.ok(dynamicIndexManager.indexStatus(typeCode));
    }

    /**
     * 校验 {@code field_config} JSON 合法性，供保存前预检。
     *
     * <p>坑：只校验不落库；配置非法时由 core 抛业务异常，错误信息会定位到具体字段。
     */
    @PostMapping("/validate-field-config")
    @Operation(summary = "校验字段配置", description = """
            校验 field_config JSON 是否合法，供保存配置前预检。只校验、不落库。
            配置非法时抛业务异常，错误信息会定位到具体字段（如字段重名、nested 字段直接聚合/排序、analyzer 用在不分词的字段上等）。""")
    public R<Void> validateFieldConfig(@RequestBody FieldConfigValidateRequest request) {
        indexTypeConfigService.validateFieldConfig(request.getFieldConfig());
        return R.ok();
    }

    // ==================================================================================
    //  删除配置的前置检查
    // ==================================================================================

    /**
     * 能不能删这个类型的配置。
     *
     * <h3>检查为什么放在控制器而不是 {@code IndexTypeConfigServiceImpl}</h3>
     * 这两条都属于「跨组件的管理策略」：一条要问 ES（该类型的索引在不在），
     * 一条要问内容表（还有没有内容）。写进 core 的服务实现里，
     * 那个类就得同时注入 {@code DynamicIndexManager} 与 {@code ContentService}，
     * 而前者的构造链已经依赖 {@code IndexTypeConfigService} —— 直接构成构造器循环依赖。
     * 换句话说，不是"放在控制器比较方便"，是那一层放不下。
     *
     * <h3>两条检查的顺序敏感</h3>
     * 索引那条必须排在内容那条前面。看起来都是"不满足就拒绝"，但把它们对调之后，
     * 一个"有内容、索引也没删"的类型会先报"还有内容"——
     * 于是操作者去删内容，删到 0 条再回来，才被告知还得先删索引。
     * 反过来先报索引，操作者的下一步动作（删索引）是**必然正确**的那一步。
     */
    private void guardDeletable(IndexTypeConfig config) {
        String typeCode = config.getTypeCode();

        // ① 索引（主索引与联想索引）必须先删干净。
        //    用 indexStatus 而不是 exists()：后者把异常吞成 false，
        //    于是「ES 连不上」会被读成「索引不存在」—— 那正好是放行删除的取值。
        Map<String, Object> status = dynamicIndexManager.indexStatus(typeCode);
        boolean mainExists = Boolean.TRUE.equals(status.get("exists"));
        boolean suggestExists = Boolean.TRUE.equals(status.get("suggestExists"));
        if (mainExists || suggestExists) {
            throw BizException.of(ResultCode.REQUEST_REJECTED,
                    "类型 " + typeCode + " 的索引还在（主索引=" + (mainExists ? "在" : "已删")
                            + "，联想索引=" + (suggestExists ? "在" : "已删")
                            + "），请先调用「删除索引」。"
                            + "配置一旦删除，该类型就被当作不存在，索引将再也删不掉（只能去 ES 手工删）");
        }

        // ② 该类型下不能还有未删除的内容。
        //    删除是逻辑删除，所以这里数的是 deleted=0 的行 —— 与检索看到的一致。
        long alive = contentService.countAliveByType(typeCode);
        if (alive > 0) {
            throw BizException.of(ResultCode.REQUEST_REJECTED,
                    "类型 " + typeCode + " 下还有 " + alive + " 条未删除的内容，"
                            + "删除配置后这些内容既搜不到也删不掉。请先处理这些内容");
        }

        log.warn("删除类型配置的前置检查已通过: typeCode={}，索引已不存在，内容条数为 0", typeCode);
    }

    /**
     * 按 id 取配置。
     *
     * <p>接口里没有「按 id 查配置」的方法，这里从 {@code listAll()} 里筛一条。
     * 不为这一处给服务接口加方法：类型配置表每种内容类型一行（个位数），
     * 而 {@code listAll} 本来就是类型管理页每次打开都要调的查询 ——
     * 多这一次的代价可以忽略，少一个接口方法就是少一处需要维护的契约。
     */
    private IndexTypeConfig findConfigById(Long id) {
        return indexTypeConfigService.listAll().stream()
                .filter(config -> id.equals(config.getId()))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("类型配置不存在: id=" + id));
    }
}
