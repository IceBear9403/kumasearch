package com.kuma.search.crawl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.dto.resp.BatchAuditResultDTO;
import com.kuma.search.repository.entity.CrawlAuditLog;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.CrawlAuditLogMapper;
import com.kuma.search.repository.mapper.SearchContentMapper;
import com.kuma.search.service.ContentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 采集内容的人工审核队列。
 *
 * <h3>这个「队列」不是一张表，是一句查询</h3>
 * 队列里是 {@code t_search_content} 里 {@code type_code='webpage' AND status IN (0,2)} 的那些行
 * —— 待审（0）与已驳回（2）<b>两种都要列</b>：命中敏感词的内容会被自动置为 2
 * （见 {@code CrawlPipeline}），若队列只列 0，词库误报就等于无声删除。
 * 靠既有的 {@code idx_type_status (type_code, status)} 索引。
 * 这是「采集内容也是内容类型」这个建模决策换来的最大红利：
 * 不新增表、不新增同步任务、不新增一套状态机，审核结果直接走既有的写链路进 ES。
 *
 * <h3>三种动作的区别，不能合并</h3>
 * <table border="1">
 *   <caption>审核动作</caption>
 *   <tr><th>动作</th><th>前置状态</th><th>结果</th><th>是否曾对外可见</th></tr>
 *   <tr><td>{@link #approve}</td><td>0 待审 / 2 已驳回</td><td>1 已发布，进 ES</td><td>否 → 是</td></tr>
 *   <tr><td>{@link #reject}</td><td>0 待审</td><td>2 驳回，<b>不进 ES</b></td><td>从未</td></tr>
 *   <tr><td>{@link #takedown}</td><td>1 已发布</td><td>2 + 从 ES 移除</td><td><b>曾经是</b></td></tr>
 * </table>
 *
 * <p>{@code REJECT} 还有一个不由人触发的来源：敏感词命中时 {@code CrawlPipeline} 会调
 * {@link #recordSystemRejection} 落一条 {@code operator='system'} 的记录，把内容置为 2。
 * 它与人工驳回走的是同一个状态、同一张日志表，差别只在 {@code operator} ——
 * 因此可以在审核列表里被正常复核、被 {@link #approve} 放行，不会因为「是机器判的」而沉底。
 *
 * <p>{@code REJECT} 与 {@code TAKEDOWN} 不能合并成一个「置为下架」的理由是：
 * 「这条内容曾经对外可见过多久」在投诉与法务场景下是必须能回答的问题。
 * 合并之后，一条被举报的内容与一条从未发布的内容在库里长得一模一样。
 *
 * <h3>为什么不直接用 ES 的 delete-by-query</h3>
 * {@code takedown} 走的是 {@code ContentService.delete} → Outbox → ES DELETE 这条既有链路，
 * 而不是直接对 ES 发删除请求。理由是重复的写入路径一定会分叉：
 * 绕过 Outbox 意味着这条删除没有留痕、没有重试、没有对账，
 * 而删除恰恰是最不能被静默漏掉的操作。
 *
 * <h3>批量审核（{@link #batchApprove} / {@link #batchReject}）</h3>
 * 采集是持续的、人审是间歇的，这个速度差必然产生积压（首次上线时就有几百条存量待审）。
 * 逐条点放行的效率问题不是"麻烦"，而是<b>积压永远清不掉</b> ——
 * 而积压的后果是内容长期不可检索，采集这件事等于白做。
 *
 * <p>三条纪律，每条都是为了不让"批量"变成"批量出错"：
 * <ol>
 *   <li><b>逐条走既有的 {@link #approve} / {@link #reject}，不写第二份实现。</b>
 *       一行 {@code update ... set status=1} 看起来更简单，但它不会写 Outbox 事件 ——
 *       结果是状态是 1、ES 里永远没有这条文档，几百条内容一起变成"审核通过了却搜不到"，
 *       而且没有任何报错。</li>
 *   <li><b>每条一个独立事务。</b>一条坏数据（并发冲突、内容已被删除）不能让其余几百条
 *       跟着回滚 —— 那正是批量操作最不能出现的失败模式：做完一大半，全部白做，
 *       而且不知道做到哪了。</li>
 *   <li><b>只批量放行与驳回，不批量撤回（{@link #takedown}）。</b>
 *       前两者只影响"能不能被搜到"，撤回则让已经对外可见过的内容消失且不可逆。
 *       "全选当前页 → 撤回"与"全选当前页 → 驳回"在界面上只差一个字，
 *       后果却相差一个数量级，因此撤回始终逐条、带二次确认。</li>
 * </ol>
 */
@Slf4j
@Service
public class CrawlAuditService {

    /** 与 {@code sql/data.sql} 中 {@code t_index_type_config} 的 webpage 行对应。 */
    public static final String TYPE_CODE_WEBPAGE = "webpage";

    /** 待审。 */
    public static final int STATUS_PENDING = 0;
    /** 已发布，进 ES。 */
    public static final int STATUS_PUBLISHED = 1;
    /** 驳回 / 已下架。 */
    public static final int STATUS_REJECTED = 2;

    /** 自动驳回时 {@code operator} 的取值。用于区分「机器判的」与「人判的」。 */
    public static final String OPERATOR_SYSTEM = "system";

    /** 批量审核按条件处理时的默认条数上限。 */
    public static final int BATCH_LIMIT_DEFAULT = 200;

    /**
     * 批量审核单次处理的条数上限（两种模式都受它约束）。
     *
     * <p>为什么一定要有上限：按条件批量是一次<b>没有天然边界的全表更新</b>。
     * 采集量涨到十万条、而筛选条件又写得很宽（比如只按域名）时，
     * 一次请求就会变成"在一个 HTTP 请求里逐条跑十万次事务" ——
     * 连接被占住、超时、调用方重试，然后同一批数据被并发的两个请求同时处理。
     * 上限把这件事变成可预期的分批：每次最多这么多，返回值里的
     * {@code remainingPending} 告诉你还剩多少，再调一次即可。
     */
    public static final int BATCH_LIMIT_MAX = 2000;

    /** 失败明细最多返回多少条。见 {@link BatchAuditResultDTO#getFailures()}。 */
    private static final int MAX_FAILURE_DETAILS = 100;

    private final SearchContentMapper searchContentMapper;
    private final CrawlAuditLogMapper crawlAuditLogMapper;
    private final ContentService contentService;

    /**
     * 批量审核里"每一条"的事务模板。
     *
     * <p><b>为什么需要它，而不是靠 {@link #approve} 上的 {@code @Transactional}：</b>
     * 批量方法调用 {@code this.approve(...)} —— 这是<b>同一个对象内部的方法调用</b>，
     * 不经过 Spring 的代理，那个注解<b>根本不会生效</b>。真正划出事务边界的是本模板。
     * （这个坑在没有批量的年代不存在，因为 approve 只会被 Controller 通过代理调用。）
     *
     * <p>用 {@code REQUIRES_NEW} 而不是默认的 {@code REQUIRED}：即使将来有人把
     * 批量方法本身加上 {@code @Transactional}，每一条也仍然是各自独立的事务 ——
     * "一条坏数据不影响其余几百条"这条性质，不能依赖调用方有没有顺手加注解。
     */
    private final TransactionTemplate perItemTransaction;

    public CrawlAuditService(SearchContentMapper searchContentMapper,
                             CrawlAuditLogMapper crawlAuditLogMapper,
                             ContentService contentService,
                             PlatformTransactionManager transactionManager) {
        this.searchContentMapper = searchContentMapper;
        this.crawlAuditLogMapper = crawlAuditLogMapper;
        this.contentService = contentService;

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perItemTransaction = template;
    }

    /**
     * 待审列表。
     *
     * <p>{@code domain} 与 {@code matchedWords} 两个筛选条件是运维最常用的两个切入口：
     * 一个是「这个站的内容质量如何」（决定要不要把它从白名单里去掉），
     * 一个是「词库里的这个词是不是误判太多」（决定要不要调词库）。
     * 没有它们，审核页就只是一个无法收敛的待办列表。
     *
     * @param status 0 待审 / 2 已驳回；传 null 表示两者都要
     */
    public Page<SearchContent> listPending(int pageNum, int pageSize, Integer status,
                                           String domain, String matchedWords) {
        LambdaQueryWrapper<SearchContent> wrapper = new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, TYPE_CODE_WEBPAGE);
        if (status != null) {
            wrapper.eq(SearchContent::getStatus, status);
        } else {
            // 只列 0 与 2：已发布的（status=1）属于「线上内容管理」，不是审核队列的一部分，
            // 把它混进来会让待办数永远不为零，审核人对这个数字也就失去感觉了。
            wrapper.in(SearchContent::getStatus, STATUS_PENDING, STATUS_REJECTED);
        }
        applyExtraFilters(wrapper, domain, matchedWords);
        wrapper.orderByDesc(SearchContent::getUpdateTime);
        return searchContentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 批量放行。参数含义与用法见 {@link #batch}。
     *
     * @param contentIds 显式指定的内容主键；为空则改用 {@code domain} / {@code matchedWords} 条件
     * @param limit      按条件模式下的条数上限；为空取 {@link #BATCH_LIMIT_DEFAULT}
     * @return 成功 / 失败 / 跳过 / 剩余待审的明细，见 {@link BatchAuditResultDTO}
     */
    public BatchAuditResultDTO batchApprove(List<Long> contentIds, String domain, String matchedWords,
                                            Integer limit, String operator, String reason) {
        return batch(BatchAction.APPROVE, contentIds, domain, matchedWords, limit, operator, reason);
    }

    /**
     * 批量驳回。参数含义与用法见 {@link #batch}。
     *
     * <p>它的典型用途不是"批量审完"，而是<b>批量排除</b>：某个站点整体质量不合格，
     * 一次把它名下的待审内容全部驳回，剩下的待审队列里就只剩需要真正逐条判断的了。
     */
    public BatchAuditResultDTO batchReject(List<Long> contentIds, String domain, String matchedWords,
                                           Integer limit, String operator, String reason) {
        return batch(BatchAction.REJECT, contentIds, domain, matchedWords, limit, operator, reason);
    }

    /**
     * 批量审核的公共实现。
     *
     * <h3>两种定位方式，二选一</h3>
     * <ul>
     *   <li><b>按 id</b>（{@code contentIds} 非空）：处理"我在列表里勾中的这几条"。
     *       此时 {@code limit} 被忽略，但总数仍受 {@link #BATCH_LIMIT_MAX} 限制 ——
     *       超出直接报参数错误，而不是<b>静默截断</b>：勾了 3000 条却只处理 2000 条，
     *       而返回里不提这回事，是比报错严重得多的失败方式。</li>
     *   <li><b>按条件</b>（{@code contentIds} 为空）：处理"这个域名下的待审内容"。
     *       这对抓取积压是必需的 —— 首次上线时库里就有几百条存量待审，
     *       靠勾选清不掉。<b>此时只作用于待审（0）</b>：对已发布（1）的内容批量动手
     *       是另一件事（撤回，不可逆），必须逐条带确认，见类注释的第三条纪律。</li>
     * </ul>
     *
     * <h3>为什么逐条走 approve/reject 而不是拼一条 UPDATE</h3>
     * 见类注释第一条纪律。这里补一句代价的账单：逐条意味着 <i>N</i> 次
     * "查一次 + 改一次 + 写一条日志 + 写一条 Outbox"，2000 条就是约八千次往返。
     * 换来的是<b>可对账</b>：每一条的成功与失败都能单独回答，而一条 UPDATE 只能说"改了 2000 行"。
     * 审核是法定义务的落点，可对账优先于速度。
     *
     * @param operator 操作者，用于审核留痕；与单条接口一致，取不到时回落为 {@code system}
     * @param reason   理由，写入同一条审核日志（不是每条不同）
     */
    private BatchAuditResultDTO batch(BatchAction action, List<Long> contentIds, String domain,
                                      String matchedWords, Integer limit, String operator, String reason) {
        List<BatchAuditResultDTO.FailureDTO> failures = new ArrayList<>();
        List<Long> targets;
        int requested;
        int skipped = 0;
        int failed = 0;

        if (contentIds != null && !contentIds.isEmpty()) {
            List<Long> unique = contentIds.stream().filter(Objects::nonNull).distinct().toList();
            if (unique.size() > BATCH_LIMIT_MAX) {
                throw BizException.paramInvalid("单次批量审核最多 " + BATCH_LIMIT_MAX
                        + " 条，本次请求 " + unique.size() + " 条。请分批提交，或改用按条件模式。");
            }
            requested = unique.size();
            Selection selection = selectByIds(unique, action, failures);
            targets = selection.targets();
            skipped = selection.skipped();
            failed = selection.missing();
        } else {
            targets = searchContentMapper
                    .selectPage(new Page<>(1, normalizeLimit(limit)), batchTargetWrapper(domain, matchedWords))
                    .getRecords().stream()
                    .map(SearchContent::getId)
                    .toList();
            requested = targets.size();
        }

        int succeeded = 0;
        for (Long contentId : targets) {
            try {
                perItemTransaction.executeWithoutResult(txStatus -> {
                    if (action == BatchAction.APPROVE) {
                        approve(contentId, operator, reason);
                    } else {
                        reject(contentId, operator, reason);
                    }
                });
                succeeded++;
            } catch (Exception e) {
                failed++;
                // 单条失败只记 WARN 并继续：批量操作的整批中止，会让"一条坏数据"变成
                // "几百条都没处理"，而调用方除了重来一遍没有别的办法 ——
                // 重来又会在同一条上再挂一次。
                addFailure(failures, contentId, briefReason(e));
                log.warn("批量{}的其中一条失败，已跳过继续: contentId={}，原因={}",
                        action.label, contentId, e.getMessage());
            }
        }

        long remaining = countPending();
        log.info("批量{}完成：请求={}，成功={}，失败={}，跳过={}，剩余待审={}，operator={}",
                action.label, requested, succeeded, failed, skipped, remaining, operator);

        return BatchAuditResultDTO.builder()
                .requested(requested)
                .succeeded(succeeded)
                .failed(failed)
                .skipped(skipped)
                .failures(failures)
                .remainingPending(remaining)
                .build();
    }

    /**
     * 按 id 定位待处理的行。
     *
     * <p>这里做两件单条接口没做的事，都是批量特有的风险：
     * <ol>
     *   <li><b>校验 type_code</b>。单条接口是人在列表上点的那一行，类型不会有疑问；
     *       而按 id 批量时，请求体里的 id 是调用方给的 —— 不校验就可能把站内内容
     *       甚至其它类型的数据一起发了出去。</li>
     *   <li><b>跳过已经是目标状态的行</b>。审核页是多人可能同时打开的快照，
     *       别人刚放行过的内容仍在你的勾选列表里。重复放行本身不报错，
     *       但会多写一条 APPROVE 日志，让"这条被审过几次"变得不可读。</li>
     * </ol>
     *
     * @param failures 找不到的行会往这里追加一条明细（"内容不存在"），其数量单独返回给调用方
     */
    private Selection selectByIds(List<Long> ids, BatchAction action,
                                  List<BatchAuditResultDTO.FailureDTO> failures) {
        List<SearchContent> rows = searchContentMapper.selectBatchIds(ids);
        Map<Long, SearchContent> byId = rows.stream()
                .collect(Collectors.toMap(SearchContent::getId, row -> row));

        List<Long> targets = new ArrayList<>();
        int skipped = 0;
        int missing = 0;

        for (Long id : ids) {
            SearchContent row = byId.get(id);
            if (row == null) {
                // 逻辑删除的行查不出来。它可能是被"撤回"了，也可能是别人删掉了 ——
                // 两种情况下"放行"都是无效动作，必须让调用方看见，不能静默算成功。
                missing++;
                addFailure(failures, id, "内容不存在（可能已被删除或撤回）");
                continue;
            }
            if (!TYPE_CODE_WEBPAGE.equals(row.getTypeCode())
                    || (row.getStatus() != null && row.getStatus() == action.targetStatus)) {
                skipped++;
                continue;
            }
            targets.add(id);
        }
        return new Selection(targets, skipped, missing);
    }

    /**
     * 按条件定位待处理的行。
     *
     * <p>固定带上 {@code status=0}：按条件批量只清理"待审"积压。
     * 已发布内容的批量处置是撤回的范畴，见 {@link #batch}。
     *
     * <p>按 {@code id} 升序：先处理最早采到的。这既让分批调用有一个稳定的推进方向
     * （每次处理完的都变成 status=1 从而退出筛选，下一批自然接上），
     * 也让"重复调用几次"这件事本身是幂等的。
     */
    private LambdaQueryWrapper<SearchContent> batchTargetWrapper(String domain, String matchedWords) {
        LambdaQueryWrapper<SearchContent> wrapper = new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, TYPE_CODE_WEBPAGE)
                .eq(SearchContent::getStatus, STATUS_PENDING);
        applyExtraFilters(wrapper, domain, matchedWords);
        return wrapper.orderByAsc(SearchContent::getId);
    }

    /** 把用户输入的上限收敛到合法区间。 */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return BATCH_LIMIT_DEFAULT;
        }
        return Math.min(limit, BATCH_LIMIT_MAX);
    }

    /** 追加一条失败明细。有上限，原因见 {@link BatchAuditResultDTO#getFailures()}。 */
    private void addFailure(List<BatchAuditResultDTO.FailureDTO> failures, Long contentId, String reason) {
        if (failures.size() >= MAX_FAILURE_DETAILS) {
            return;
        }
        failures.add(BatchAuditResultDTO.FailureDTO.builder()
                .contentId(contentId)
                .reason(reason)
                .build());
    }

    /** 异常 → 可读的失败原因。给运维看的，因此不暴露类名（除非连 message 都没有）。 */
    private String briefReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return truncate(message, 200);
    }

    /**
     * {@code extra} JSON 里两个运维筛选条件。
     *
     * <p>列表与批量审核共用同一份实现：两边都是"先筛出这一批，再做事"，
     * 如果筛选口径不一致，就会出现「列表里显示 30 条，批量却动了 45 条」这种
     * 最难排查的一类问题。
     */
    private void applyExtraFilters(LambdaQueryWrapper<SearchContent> wrapper,
                                   String domain, String matchedWords) {
        if (domain != null && !domain.isBlank()) {
            // extra 是 JSON 字符串，用 LIKE 匹配而非解析 —— MySQL 的 JSON 函数需要额外索引，
            // 而审核量级下（人工逐条看）全表扫一遍是可接受的。数据量涨上来时再考虑生成列 + 索引。
            wrapper.like(SearchContent::getExtra, "\"domain\":\"" + domain.trim() + "\"");
        }
        if (matchedWords != null && !matchedWords.isBlank()) {
            wrapper.like(SearchContent::getExtra, "\"matchedWords\":\"" + matchedWords.trim() + "\"");
        }
    }

    /** 可批量执行的动作。<b>刻意没有 TAKEDOWN</b>，理由见类注释的第三条纪律。 */
    private enum BatchAction {

        APPROVE("放行", STATUS_PUBLISHED),
        REJECT("驳回", STATUS_REJECTED);

        /** 日志用词。 */
        private final String label;
        /** 处理成功后的状态。用于判断"这条已经在目标状态了，跳过"。 */
        private final int targetStatus;

        BatchAction(String label, int targetStatus) {
            this.label = label;
            this.targetStatus = targetStatus;
        }
    }

    /**
     * 按 id 定位的结果。
     *
     * @param targets 真正要处理的行
     * @param skipped 跳过的条数（类型不符 / 已在目标状态）
     * @param missing 查不到的条数（计入失败，明细已在 failures 里）
     */
    private record Selection(List<Long> targets, int skipped, int missing) {
    }

    /**
     * 放行：置为已发布，走既有写链路进 ES。
     *
     * <p>调用 {@code ContentService.save} 而不是直接 update 状态：只有走 save 才会
     * 在同一个事务里写出 Outbox 事件，ES 才会有这条文档。直接 update 的状态是 1，
     * 但搜索里永远找不到它 —— 一个「看起来审核通过了其实没生效」的静默故障。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long contentId, String operator, String reason) {
        SearchContent content = requireContent(contentId);
        content.setStatus(STATUS_PUBLISHED);
        contentService.save(content);
        writeLog(content, CrawlAuditLog.ACTION_APPROVE, operator, reason);
        log.info("采集内容已放行: contentId={} operator={}", contentId, operator);
    }

    /**
     * 驳回：置为已驳回，不进 ES。
     *
     * <p>同样走 {@code save}：若这条内容此前已经发布过（有人先放行、后又反悔），
     * 也需要让 ES 里的文档状态同步为 2。查询侧强制带上 {@code status=1} 过滤，
     * 因此状态一改，它立刻从搜索结果里消失，不需要删除文档。
     * 这也意味着「驳回」是可逆的 —— 再放行一次即可，而删除不可逆。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long contentId, String operator, String reason) {
        SearchContent content = requireContent(contentId);
        content.setStatus(STATUS_REJECTED);
        contentService.save(content);
        writeLog(content, CrawlAuditLog.ACTION_REJECT, operator, reason);
        log.info("采集内容已驳回: contentId={} operator={} reason={}", contentId, operator, reason);
    }

    /**
     * 撤回（下架）：从 ES 中移除。
     *
     * <p>与 {@link #reject} 的差别见类注释 —— 这条路用于「内容曾经公开发布过，
     * 现在因为投诉或发现违规必须撤下」。走 {@code ContentService.delete}，
     * 同时逻辑删除 MySQL 行并触发 ES 删除。
     *
     * <p>内容行被逻辑删除后，本条记录还能靠 {@code t_crawl_audit_log.url_hash} 追溯 ——
     * 这正是审核日志表要冗余存 urlHash 的原因。
     *
     * <p>⚠️ <b>这个方法不能省掉人工确认环节。</b>撤回意味着内容对已经看过它的人消失，
     * 若被误触（例如把「驳回」错点成「撤回」），作者与用户都无从感知。
     */
    @Transactional(rollbackFor = Exception.class)
    public void takedown(Long contentId, String operator, String reason) {
        SearchContent content = requireContent(contentId);
        // 记录要在删除之前写：delete 之后 content 行已被逻辑删除，
        // 拿不到 extra 里的 urlHash，日志就会缺掉最关键的那个字段
        writeLog(content, CrawlAuditLog.ACTION_TAKEDOWN, operator, reason);
        contentService.delete(TYPE_CODE_WEBPAGE, content.getBizId());
        log.warn("采集内容已撤回下架: contentId={} bizId={} operator={} reason={}",
                contentId, content.getBizId(), operator, reason);
    }

    /**
     * 记录一次由规则自动产生的驳回（敏感词命中）。
     *
     * <p>由 {@code CrawlPipeline} 调用。<b>单独一个方法而不是复用 {@link #reject} 的日志部分</b>，
     * 是因为自动驳回的语义是「已按规则置为不可发布 + 留痕等人复核」，
     * 它不应当在代码上被误用成「某个管理员点了驳回」。
     *
     * @param matchedWords 命中的词，逗号分隔
     */
    public void recordSystemRejection(String urlHash, Long contentId, String matchedWords, String reason) {
        CrawlAuditLog log = CrawlAuditLog.builder()
                .urlHash(urlHash)
                .contentId(contentId)
                .action(CrawlAuditLog.ACTION_REJECT)
                .operator(OPERATOR_SYSTEM)
                .reason(reason)
                .matchedWords(truncate(matchedWords, 500))
                .build();
        crawlAuditLogMapper.insert(log);
    }

    /**
     * 查某条 URL 的审核历史。
     *
     * <p>按 {@code url_hash} 而不是 {@code content_id} 查：内容被撤回后（逻辑删除、
     * 甚至物理清理）{@code content_id} 会查不到东西，而 urlHash 永远可重算。
     */
    public Page<CrawlAuditLog> listLogs(int pageNum, int pageSize, String urlHash) {
        LambdaQueryWrapper<CrawlAuditLog> wrapper = new LambdaQueryWrapper<CrawlAuditLog>()
                .orderByDesc(CrawlAuditLog::getCreateTime);
        if (urlHash != null && !urlHash.isBlank()) {
            wrapper.eq(CrawlAuditLog::getUrlHash, urlHash.trim());
        }
        return crawlAuditLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /** 待审与已驳回的总数，用于后台角标。 */
    public long countPending() {
        LambdaQueryWrapper<SearchContent> wrapper = new LambdaQueryWrapper<SearchContent>()
                .eq(SearchContent::getTypeCode, TYPE_CODE_WEBPAGE)
                .eq(SearchContent::getStatus, STATUS_PENDING);
        return searchContentMapper.selectCount(wrapper);
    }

    private SearchContent requireContent(Long contentId) {
        SearchContent content = searchContentMapper.selectById(contentId);
        if (content == null) {
            // 用现有的结果码体系，不新造一个 —— 语义就是「找不到」
            throw BizException.notFound("采集内容不存在: " + contentId);
        }
        return content;
    }

    private void writeLog(SearchContent content, String action, String operator, String reason) {
        CrawlAuditLog log = CrawlAuditLog.builder()
                .urlHash(content.getBizId())
                .contentId(content.getId())
                .action(action)
                .operator(operator == null || operator.isBlank() ? OPERATOR_SYSTEM : operator)
                .reason(truncate(reason, 500))
                .matchedWords(extractMatchedWords(content))
                .build();
        crawlAuditLogMapper.insert(log);
    }

    /**
     * 从 extra 里取出命中的敏感词，冗余存进审核日志。
     *
     * <p>见 {@link CrawlAuditLog#matchedWords} 的说明：词库会被持续修改，
     * 事后再去 extra 里查只会得到「用今天的词库判昨天的事」这个错误答案。
     *
     * <p>这里用的是字符串查找而不是 JSON 解析：只为了取一个字段而引入
     * JSON 解析的开销与异常分支不划算，取不到就返回 null —— 这个字段是辅助信息，
     * 缺失不应影响审核动作本身。
     */
    private String extractMatchedWords(SearchContent content) {
        String extra = content.getExtra();
        if (extra == null) {
            return null;
        }
        String key = "\"matchedWords\":\"";
        int start = extra.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = extra.indexOf('"', start);
        return end < 0 ? null : extra.substring(start, end);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
