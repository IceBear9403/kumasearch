package com.kuma.search.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.constant.ContentFields;
import com.kuma.search.crawl.SensitiveWordFilter;
import com.kuma.search.dto.resp.ContentFieldSchemaDTO;
import com.kuma.search.dto.resp.ContentTypeSchemaDTO;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.service.ContentService;
import com.kuma.search.service.IndexTypeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 前台用户投稿 —— 站内内容的一条**对外开放的写入链路**。
 *
 * <h3>它与管理后台的内容写入有什么区别</h3>
 * 后台那条路（{@code ContentAdminService}）信任操作者：调用方已经过了
 * {@code admin:content:manage}，逐条设状态、改作者、调权重都是它的正常工作。
 * 这条路面对的是**任何登录用户**，因此每一个字段都要问一次"凭什么由他说了算"，
 * 由此产生三条不可省略的规则：
 *
 * <ol>
 *   <li><b>归属由服务端写死</b>：{@code authorId} 取当前登录用户，忽略请求体。
 *       不这么做就能把稿子挂到别人名下，而"只能改自己的稿子"这条规则正是靠它判断的。</li>
 *   <li><b>已发布不可自助修改</b>：可以先投一篇正常的稿子过审，再把内容改成任意东西 ——
 *       等于绕开了整个审核队列。改稿只允许发生在待审（0）与已驳回（2）状态，
 *       且改完强制回到待审。</li>
 *   <li><b>字段白名单</b>：{@code extra} 是自由 JSON，不设白名单就等于让任何登录用户
 *       往 ES 文档里塞任意键值（虽然 {@code DocumentConverter} 只输出 field_config
 *       里声明过的字段，但"靠下游过滤"和"在这里就拒绝"是两件事，
 *       前者会让越权写入静默成功）。</li>
 * </ol>
 *
 * <h3>审核队列没有为它新建一张表</h3>
 * 投稿落 {@code status=0}，就是既有的"待审"语义：ES 里会有文档，但
 * {@code SearchServiceImpl} 在查询期强制并入 {@code status=1}，所以搜不到。
 * 放行动作因此只是"把 status 改成 1"，走的是既有的内容保存接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSubmitService {

    /** 待审。投稿一律落这个状态。 */
    private static final int STATUS_PENDING = 0;
    /** 已发布（审核通过）。 */
    private static final int STATUS_PUBLISHED = 1;
    /** 已驳回（含命中敏感词自动驳回）。 */
    private static final int STATUS_REJECTED = 2;

    /** 投稿生成 bizId 的前缀。与后台手工录入（m-）、采集（url_hash）区分开，出问题时一眼能看出这条是谁写的。 */
    private static final String BIZ_ID_PREFIX = "s-";

    /**
     * {@code extra} 的解析目标类型。
     *
     * <p>抽成常量而不是每次 {@code readValue(json, Map.class)}：后者返回裸 {@code Map}，
     * 赋给 {@code Map<String, Object>} 时是未检查转换（编译器警告），
     * 且真的拿到非字符串键时不会在解析处失败，而是把问题推迟到某个下游的 {@code get}。
     */
    private static final TypeReference<Map<String, Object>> EXTRA_TYPE =
            new TypeReference<>() {
            };

    private final ContentAdminService contentAdminService;
    private final ContentService contentService;
    private final IndexTypeConfigService indexTypeConfigService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final ObjectMapper objectMapper;

    // ==================================================================================
    //  读
    // ==================================================================================

    /** 允许投稿的类型结构（只含 {@code allow_user_submit=1} 的启用类型）。 */
    public List<ContentTypeSchemaDTO> listSubmittableSchemas() {
        return indexTypeConfigService.listContentSchemas(true);
    }

    /**
     * 我的投稿。
     *
     * <p>这里用 {@code authorId} 过滤而不是"查出来再筛"：后者在分页下是错的 ——
     * 先按页取 10 条再过滤掉别人的，本页就只剩几条，而总数还是全量。
     */
    public PageResult<SearchContent> mySubmissions(Long userId, String typeCode, int pageNum, int pageSize) {
        return contentAdminService.page(typeCode, pageNum, pageSize, null, null, String.valueOf(userId));
    }

    // ==================================================================================
    //  写
    // ==================================================================================

    /** 新增投稿。 */
    public Long submit(Long userId, SearchContent request) {
        ContentTypeSchemaDTO schema = requireSubmittable(request.getTypeCode());

        // bizId 由服务端生成：它是 ES 文档 _id 的来源，让投稿人指定等于允许覆盖别人的文档。
        // 前缀可读而非纯 UUID，是为了在库里一眼区分"人写的/机器抓的/后台录的"。
        request.setBizId(BIZ_ID_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        request.setId(null);
        forceServerManaged(request, schema);
        request.setAuthorId(String.valueOf(userId));
        request.setStatus(STATUS_PENDING);

        applySensitiveWordRule(request, schema);

        return contentService.save(request);
    }

    /** 修改自己的投稿。 */
    public void update(Long userId, SearchContent request) {
        ContentTypeSchemaDTO schema = requireSubmittable(request.getTypeCode());
        if (!StringUtils.hasText(request.getBizId())) {
            throw BizException.paramInvalid("bizId 不能为空");
        }

        SearchContent existing = requireOwn(userId, request.getTypeCode(), request.getBizId());
        requireEditable(existing);

        request.setId(null);
        forceServerManaged(request, schema);
        request.setAuthorId(String.valueOf(userId));
        // 改完回到待审：驳回后编辑过的是**新的一版**，必须重新看一遍。
        // 否则"先投一篇合规的、过审后再改成别的"只需要多一步驳回即可绕过审核。
        request.setStatus(STATUS_PENDING);

        applySensitiveWordRule(request, schema);

        contentService.save(request);
        log.info("用户 {} 修改了投稿 {}/{}", userId, request.getTypeCode(), request.getBizId());
    }

    /** 撤回自己的投稿。 */
    public void withdraw(Long userId, String typeCode, String bizId) {
        requireSubmittable(typeCode);
        SearchContent existing = requireOwn(userId, typeCode, bizId);
        requireEditable(existing);

        contentService.delete(typeCode, bizId);
        log.info("用户 {} 撤回了投稿 {}/{}", userId, typeCode, bizId);
    }

    // ==================================================================================
    //  规则
    // ==================================================================================

    /** 该类型必须存在、启用、且开放投稿。 */
    private ContentTypeSchemaDTO requireSubmittable(String typeCode) {
        if (!StringUtils.hasText(typeCode)) {
            throw BizException.paramInvalid("typeCode 不能为空");
        }
        ContentTypeSchemaDTO schema = indexTypeConfigService.getContentSchema(typeCode);
        if (!schema.isAllowUserSubmit()) {
            throw BizException.paramInvalid(
                    "类型 " + typeCode + " 未开放投稿。开放与否由类型配置里的「允许前台投稿」决定");
        }
        return schema;
    }

    /**
     * 取一条**属于当前用户**的投稿。
     *
     * <p>别人的内容返回 {@code CONTENT_NOT_FOUND} 而<b>不是</b> {@code ACCESS_DENIED}：
     * 403 等于告诉对方"这个 bizId 确实存在，只是不归你"，于是这个接口就成了
     * 一个可以枚举他人内容的探针（"存在"与"不存在"响应不同）。
     * 报"不存在"则两种情况完全一致。
     */
    private SearchContent requireOwn(Long userId, String typeCode, String bizId) {
        if (!StringUtils.hasText(bizId)) {
            throw BizException.paramInvalid("bizId 不能为空");
        }
        SearchContent existing;
        try {
            existing = contentService.get(typeCode, bizId);
        } catch (BizException e) {
            // 只把「不存在」统一成本方法的文案，其它异常（参数问题、DB 故障）原样抛出 ——
            // 一律吞成"投稿不存在"会把真实故障伪装成 404，排查时会先怀疑用户传错了 ID。
            if (e.getResultCode() != ResultCode.CONTENT_NOT_FOUND) {
                throw e;
            }
            throw notFound(typeCode, bizId);
        }
        // 不是自己的内容：与"不存在"返回完全一致的响应，不给枚举他人 bizId 的探针留观察点。
        if (!String.valueOf(userId).equals(existing.getAuthorId())) {
            throw notFound(typeCode, bizId);
        }
        return existing;
    }

    private BizException notFound(String typeCode, String bizId) {
        return BizException.of(ResultCode.CONTENT_NOT_FOUND, "投稿不存在: " + typeCode + "/" + bizId);
    }

    /**
     * 只有待审（0）与已驳回（2）的投稿可以自助修改或撤回。
     *
     * <p>已发布（1）为什么不行：那是<b>在线可见</b>的内容，用户能改它等于绕开审核；
     * 撤回同理 —— 一条内容的可见性变化属于审核动作，不归作者。要改就先联系管理员下架。
     */
    private void requireEditable(SearchContent existing) {
        if (existing.getStatus() != null && existing.getStatus() == STATUS_PUBLISHED) {
            throw BizException.of(ResultCode.ACCESS_DENIED,
                    "已发布的投稿不能自行修改或撤回。如需改动，请联系管理员先下架");
        }
    }

    /**
     * 覆盖由服务端管理的字段（见 {@code ContentFields#SERVER_MANAGED_ON_SUBMIT}）。
     *
     * <p>为什么是"覆盖"而不是"拒绝请求"：这些字段在请求体里出现往往只是前端把整行数据回传了，
     * 报错会让正常的编辑流程莫名失败。真正的风险是它们的**值被采信**，所以置空即可 ——
     * {@code ContentService} 的更新只写非 null 字段，置空等于"这个字段不动"。
     *
     * <p>{@code deleted} 单独处理：它必须显式置空。否则请求体里带一个
     * {@code deleted: 1} 就能通过更新接口把自己的稿子"删掉"，
     * 而这条路径会绕过 {@code ContentService#delete}，ES 侧的文档不会被删除。
     */
    private void forceServerManaged(SearchContent request, ContentTypeSchemaDTO schema) {
        request.setBizWeight(null);
        request.setDeleted(null);
        validateExtra(request, schema);
    }

    /**
     * extra 的字段白名单校验。
     *
     * <p>允许写的是"该类型 field_config 里声明过、且不是保留字段"的键。
     * 保留字段（id / bizId / typeCode）与服务端管理字段（status / authorId / bizWeight）
     * 即使声明了也不允许由这里写入 —— 前者是文档身份，后者是审核结论。
     */
    private void validateExtra(SearchContent request, ContentTypeSchemaDTO schema) {
        if (!StringUtils.hasText(request.getExtra())) {
            return;
        }
        Map<String, Object> extra;
        try {
            extra = objectMapper.readValue(request.getExtra(), EXTRA_TYPE);
        } catch (Exception e) {
            throw BizException.paramInvalid("extra 不是合法的 JSON 对象");
        }
        if (extra == null || extra.isEmpty()) {
            return;
        }

        Set<String> allowed = new HashSet<>();
        if (schema.getFields() != null) {
            for (ContentFieldSchemaDTO field : schema.getFields()) {
                allowed.add(field.getName());
            }
        }

        for (String key : extra.keySet()) {
            if (!allowed.contains(key)) {
                throw BizException.paramInvalid(
                        "字段 " + key + " 未在类型 " + schema.getTypeCode()
                                + " 的 field_config 中声明，投稿不允许写入该字段");
            }
            if (ContentFields.SERVER_MANAGED_ON_SUBMIT.contains(key)) {
                throw BizException.paramInvalid("字段 " + key + " 由系统管理，不能由投稿设置");
            }
        }
    }

    /**
     * 敏感词过滤：命中即落「已驳回」，并把命中的词写进 {@code extra.matchedWords}。
     *
     * <p>与采集链路同一个词库、同一个 key（见 {@code CrawlPipeline#buildExtra}）：
     * 两处的自动驳回理由必须能被人用同一句话解释，否则"为什么这条被驳回了"
     * 会有两套答案。词库在 {@code t_search_dict} 里按 {@code dict_type=SENSITIVE} 维护。
     *
     * <p>它<b>不是</b>安全承诺：只能拦住词库里已有的词。人工审核仍是必需的补充，
     * 这一点与采集侧一致。
     */
    private void applySensitiveWordRule(SearchContent request, ContentTypeSchemaDTO schema) {
        List<String> matched = sensitiveWordFilter.match(
                (request.getTitle() == null ? "" : request.getTitle()) + "\n"
                        + (request.getContent() == null ? "" : request.getContent()));
        if (matched.isEmpty()) {
            return;
        }

        request.setStatus(STATUS_REJECTED);
        request.setExtra(mergeMatchedWords(request.getExtra(), matched));
        log.info("投稿命中敏感词，已自动驳回：type={}，命中数={}", schema.getTypeCode(), matched.size());
    }

    /** 把命中词并进 extra JSON（保留用户已经填过的业务字段）。 */
    private String mergeMatchedWords(String extraJson, List<String> matched) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (StringUtils.hasText(extraJson)) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(extraJson, EXTRA_TYPE);
                if (parsed != null) {
                    extra.putAll(parsed);
                }
            } catch (Exception ignored) {
                // 上面 validateExtra 已经把非法 JSON 拦掉了，这里只是防御性分支：
                // 解析不了就以"只有命中词"的 extra 落库 —— 驳回理由比用户填的扩展字段重要。
            }
        }
        extra.put("matchedWords", String.join(",", matched));
        try {
            return objectMapper.writeValueAsString(extra);
        } catch (Exception e) {
            log.warn("投稿命中词写入 extra 失败，本次不记录命中词，原因={}", e.getMessage());
            return extraJson;
        }
    }
}
