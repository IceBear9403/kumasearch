package com.kuma.search.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.repository.entity.CrawlSeed;
import com.kuma.search.repository.entity.CrawlUrl;
import com.kuma.search.repository.entity.SearchContent;
import com.kuma.search.repository.mapper.CrawlSeedMapper;
import com.kuma.search.service.ContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采集编排：取任务 → 校验范围 → 判 robots → 限速 → 抓 → 抽 → 过滤 → 入库 → 送审。
 *
 * <p>这个类本身不含任何「怎么做」的逻辑，只负责<b>顺序与短路</b>：
 * 每一步都可以终止整条流程，且终止的方式（终态 / 重试 / 推迟）决定了这个 URL 的命运。
 * 把顺序集中在一处，是为了让「这个 URL 为什么没被采到」这个问题有唯一答案 ——
 * 逻辑分散时，同一个页面可能因为五处不同的判断被跳过，而排查者只能一处一处猜。
 *
 * <h3>短路顺序不是随意的，每一步都在为后面的步骤省钱</h3>
 * <ol>
 *   <li><b>范围校验</b>（最便宜，纯字符串）—— 把已经采够的、范围外的先扔掉；</li>
 *   <li><b>robots</b> —— 命中缓存的判定，不通过就永远不采（合规红线，必须排在发请求之前）；</li>
 *   <li><b>限速</b> —— 不为拿不到配额的 URL 发请求；</li>
 *   <li><b>条件请求</b> —— 304 直接结束，连正文都不读；</li>
 *   <li><b>正文指纹未变</b> —— <b>这一步省掉的工作量最大</b>：跳过写库、跳过写 ES、跳过重新送审。
 *       列表页、导航页这类「链接常变但正文不变」的页面，重抓时绝大多数命中这里。</li>
 * </ol>
 * 把第 5 步放在敏感词过滤与入库之前，是它有价值的前提；放在后面就等于做完了全部工作再丢弃。
 *
 * <h3>⚠️ 这个类不做的两件事</h3>
 * <ul>
 *   <li><b>不做并发</b> —— 一个实例一次处理一个 URL。并发由调度器按域名分组控制，
 *       见 {@link CrawlRateLimiter} 关于「单域名并发上限 1」的说明。</li>
 *   <li><b>不决定「能不能采」</b> —— 种子是否启用、是否在范围内、robots 是否允许，
 *       全部来自配置与外部判定。这里只执行，不重新解释。</li>
 * </ul>
 *
 * <h3>按 {@code source_type} 选抽取器 —— 两种来源，同一条流水线</h3>
 * <table border="1">
 *   <caption>本类要伺候的两种来源</caption>
 *   <tr><th>{@code source_type}</th><th>响应体</th><th>抽取器</th><th>正文长度下限</th></tr>
 *   <tr><td>{@link CrawlSeed#SOURCE_TYPE_HTML}（默认）</td><td>HTML</td>
 *       <td>{@link JsoupContentExtractor} 或 {@link RegexContentExtractor}（二选一）</td>
 *       <td>{@value #MIN_TEXT_LENGTH}</td></tr>
 *   <tr><td>{@link CrawlSeed#SOURCE_TYPE_MEDIAWIKI_API}</td><td>JSON</td>
 *       <td>{@link MediaWikiApiExtractor}</td>
 *       <td>{@value #MIN_TEXT_LENGTH_API}</td></tr>
 * </table>
 * 两个抽取器是<b>并列</b>关系而不是"默认 + 覆盖"，类型上怎么区分见 {@link ResponseExtractor}。
 * 除了抽取器，这个字段还影响一件事：API 型来源<b>跳过 robots 判定</b>，
 * 理由写在 {@link #checkRobots} 里 —— 那是一处刻意的行为差异，读代码时容易误以为是漏洞。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlPipeline {

    /**
     * 正文长度下限。低于它的页面按「抽取失败」处理。
     *
     * <p>80 字是经验值：一个正常的正文页面（哪怕只是标题 + 一段话）都在 200 字以上，
     * 而 80 字以下几乎必然落在这些情况里：JS 骨架页、跳转提示页、
     * 「请开启 JavaScript」提示页、验证码拦截页。这些页面进了审核队列
     * 只会让人一遍遍地点「驳回」，消耗的是审核人的注意力。
     */
    private static final int MIN_TEXT_LENGTH = 80;

    /**
     * API 型来源（{@link CrawlSeed#SOURCE_TYPE_MEDIAWIKI_API}）的正文长度下限。
     *
     * <p>比 HTML 的 80 字低得多，因为 80 这个数字针对的是<b>HTML 特有的失败形态</b>：
     * JS 骨架页、跳转提示页、「请开启 JavaScript」、验证码拦截页。
     * 这些页面在 JSON API 的响应里不存在 —— 对方要么返回结构化数据，要么返回
     * 明确的 {@code error} 对象（{@code MediaWikiApiExtractor} 已把后者判为抽取失败）。
     *
     * <p>剩下的下限只为一件事：挡掉「正文其实没有、只是回了一个标题」的响应。
     * 20 字仍然是个经验值，但它误伤的是一篇至少 20 字的摘要 ——
     * 维基类站点的引言极少短到这个程度，而真有那么短的条目，
     * 它的检索价值也确实接近零。
     */
    private static final int MIN_TEXT_LENGTH_API = 20;

    /** 摘要长度上限。与 {@code kuma.search.summary-length} 无关 —— 那个是检索结果的摘要截断长度。 */
    private static final int SUMMARY_MAX_LENGTH = 300;

    /**
     * robots.txt 暂时读不到时，把这个 URL 推迟多久再看一次。
     *
     * <p>与 {@code RobotsTxtService} 里「不可用标记」的 10 分钟 TTL 同量级：
     * 标记还在的时候重复尝试只会白白多一次 Redis 查询，标记刚过期时正好能立刻恢复采集。
     */
    private static final long ROBOTS_UNAVAILABLE_DEFER_MILLIS = 10 * 60 * 1000L;

    private final KumaSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final CrawlSeedMapper crawlSeedMapper;
    private final CrawlFrontier crawlFrontier;
    private final UrlNormalizer urlNormalizer;
    private final RobotsTxtService robotsTxtService;
    private final CrawlRateLimiter crawlRateLimiter;
    private final PageFetcher pageFetcher;
    private final ContentExtractor contentExtractor;
    private final MediaWikiApiExtractor mediaWikiApiExtractor;
    private final ContentFingerprint contentFingerprint;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final ContentService contentService;
    private final CrawlAuditService crawlAuditService;

    /**
     * 处理一个已认领的 URL。
     *
     * <p><b>本方法不抛异常。</b>它面向的是不可控的第三方页面，任何一步都可能以意外的方式失败，
     * 而一个未捕获的异常会中断整批采集。失败一律转化为该 URL 的状态变更，
     * 让下一个 URL 继续被处理 —— 采集的正确姿势是「尽量多采成功的，把失败的记下来」，
     * 而不是「有一条失败就整批停下来」。
     */
    public void process(CrawlUrl url) {
        try {
            doProcess(url);
        } catch (RuntimeException e) {
            // 兜底。走到这里说明是代码缺陷而不是可预期的网页错误 ——
            // 页面的问题在各自的分支里已经被处理掉了。
            log.error("采集处理异常（代码缺陷或未预期的运行时错误）: url={}", url.getUrl(), e);
            crawlFrontier.markRetry(url, url.getHttpStatus() == null ? PageFetchResult.NO_RESPONSE : url.getHttpStatus(),
                    "内部异常: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void doProcess(CrawlUrl url) {
        CrawlSeed seed = url.getSeedId() == null ? null : crawlSeedMapper.selectById(url.getSeedId());
        if (seed == null || !seed.isEnabled()) {
            // 种子被删或被停用。不是这个 URL 的错，置跳过而不是重试 ——
            // 重试一万次种子也不会重新启用，只会在日志里刷同样的记录。
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED, "种子站不存在或已停用");
            return;
        }

        String scopeReject = scopeRejectReason(url, seed);
        if (scopeReject != null) {
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED, scopeReject);
            return;
        }

        if (!checkRobots(url, seed)) {
            return;
        }

        if (!acquireRate(url, seed)) {
            return;
        }

        PageFetchResult result = pageFetcher.fetch(url.getUrl(), url.getEtag(), url.getLastModified());

        if (result.isNotModified()) {
            // 304：内容未变。沿用上次的指纹，不需要读正文，也不需要重新送审
            log.debug("条件请求命中 304: {}", url.getUrl());
            crawlFrontier.markDone(url, url.getContentHash(), 304, url.getEtag(), url.getLastModified());
            return;
        }

        if (!result.isSuccess()) {
            handleFetchFailure(url, result);
            return;
        }

        // 重定向可能把我们带到范围之外（例如全站跳转到另一个域名）。
        // 这一步必须在抽取之前：否则会把范围外站点的内容也采进来，而种子白名单形同虚设。
        if (!isInScope(seed, result.getFinalUrl())) {
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED,
                    "重定向后落在采集范围之外: " + result.getFinalUrl());
            return;
        }

        // ---- 抽取：按种子站的 source_type 选实现 ----
        // 两个实现是并列的、不是"默认 + 覆盖"的关系，理由见 ResponseExtractor 的类注释。
        boolean apiSource = seed.isMediaWikiApi();
        ResponseExtractor extractor = apiSource ? mediaWikiApiExtractor : contentExtractor;
        ResponseExtractor.ExtractedContent extracted = extractor.extract(result.getBody(), url.getUrl());
        if (extracted == null) {
            // 两种来源的失败原因完全不同，分开表述：混成一句会让人按错的方向去排查。
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED, apiSource
                    ? "MediaWiki API 响应无法解析（被拦截、参数不合法或接口报错）"
                    : "正文抽取失败（疑似 JS 渲染页或拦截页）");
            return;
        }

        // ⭐ 链接入队排在长度判定**之前**，且与后面每一步都无关。
        //
        // 原先是"先判正文长度，过短就整页丢弃"，于是 <b>过短页面的链接一起被丢掉</b> ——
        // 而"正文短但链接多"正是列表页/索引页/分页页的典型形态，
        // 丢掉它们的链接等于整棵子树都不再被发现，且没有任何报错。
        // MediaWiki 的枚举响应更是极端情况：它就是"没有正文，只有一批链接"，
        // 按旧的顺序这种来源<b>在原理上</b>就采不到东西。
        //
        // 代价是重复的入队尝试（正文未变的页面也会再入队一遍）。
        // 可以接受：去重由 t_crawl_url 的 uk_url_hash 保证，
        // 而"这个 URL 曾经出现过"本身就是入队的充分理由 —— 它是否已采过，
        // 是队列自己要知道的事，不该由调用方替它判断。
        int enqueued = enqueueDiscoveredLinks(seed, extracted, url);

        int minTextLength = apiSource ? MIN_TEXT_LENGTH_API : MIN_TEXT_LENGTH;
        if (extracted.isTooShort(minTextLength)) {
            // 不入库，但链接已经在上一步入队了。日志与 error_msg 里带上链接数：
            // 「已跳过」这个状态单独看会让人以为这页白抓了，而它其实贡献了 N 个新 URL。
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED,
                    "正文过短（" + length(extracted.text()) + " < " + minTextLength
                            + " 字），不入库；已入队 " + enqueued + " 条链接");
            log.debug("正文过短不入库: url={} 长度={} 已入队链接={} 条",
                    url.getUrl(), length(extracted.text()), enqueued);
            return;
        }

        String contentHash = contentFingerprint.sha256(extracted.text());
        if (contentHash == null) {
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED, "正文为空，无法生成指纹");
            return;
        }
        if (url.isContentUnchanged(contentHash)) {
            // ⭐ 整条链路里性价比最高的一步：正文没变就不必写库、不必写 ES、不必重新送审。
            // 只更新抓取元数据（ETag 等），让下次还能走 304 这条路。
            log.debug("正文未变，跳过写库与送审: {}", url.getUrl());
            crawlFrontier.markDone(url, contentHash, result.getHttpStatus(),
                    result.getEtag(), result.getLastModified());
            return;
        }

        // 敏感词检测针对「标题 + 正文」。标题单独检一遍是必须的：
        // 违规内容常常只放在标题里，而正文可能是正常的中性描述。
        List<String> matched = sensitiveWordFilter.match(extracted.title() + "\n" + extracted.text());

        Long contentId = persist(url, seed, extracted, contentHash, matched);

        if (!matched.isEmpty()) {
            // 命中就直接置为驳回（status=2），而<b>不是</b>留在待审（status=0）等自动发布去处理：
            // 待审意味着「等人决定它能不能发布」，而这里已经按规则判定它不能发布。
            // 差别在于审核队列的语义 —— 把自动驳回的混进待审队列，
            // 会让「待审数」这个指标失去意义（审核人无法用它判断还剩多少活）。
            //
            // ⚠️ 这条判断<b>必须在</b> auto-publish 之前生效（见 resolveInitialStatus）：
            // 自动发布放行的是"没被规则拦住的内容"，若把顺序写反，命中的内容照样会被发布出去，
            // 而本地词库这道闸也就等于不存在了。
            // 自动驳回的内容仍可复核：查 t_crawl_audit_log 里 operator='system' 的 REJECT 记录。
            crawlAuditService.recordSystemRejection(url.getUrlHash(), contentId,
                    String.join(",", matched), "命中本地敏感词库，已按规则驳回，待人工复核");
            log.warn("采集内容命中敏感词，已自动驳回: url={} words={}", url.getUrl(), matched);
        }

        // 链接已在本方法开头入队（那时还没有任何提前返回的可能），此处不再重复。
        crawlFrontier.markDone(url, contentHash, result.getHttpStatus(),
                result.getEtag(), result.getLastModified());
    }

    /**
     * 范围校验：域名、子域名、路径前缀、深度。
     *
     * @return 拒绝原因；通过时返回 null
     */
    private String scopeRejectReason(CrawlUrl url, CrawlSeed seed) {
        if (!urlNormalizer.inScope(url.getUrl(), seed.getDomain(), seed.allowsSubdomain())) {
            return "不在种子站的域名范围内: " + url.getDomain();
        }
        if (!urlNormalizer.matchPathPrefix(url.getUrl(), seed.getPathPrefix())) {
            return "不在配置的路径前缀内: " + seed.getPathPrefix();
        }
        int depth = url.getDepth() == null ? 0 : url.getDepth();
        if (depth > seed.effectiveMaxDepth()) {
            return "层级 " + depth + " 超过配置的上限 " + seed.effectiveMaxDepth();
        }
        if (seed.isQuotaExhausted(crawlFrontier.countFetched(seed.getId()))) {
            // 配额用尽与「种子停用」不同：前者是采集够多了，后者是人不让采。
            // 但处置相同（不再采），因此共用跳过状态；区别体现在 reason 里，便于排查。
            return "种子页数配额已用尽（上限 " + seed.effectiveMaxPages() + "）";
        }
        return null;
    }

    /**
     * 判定 robots 是否允许。
     *
     * <p>三种结果对应三种处置，<b>不能合并</b>（见 {@link RobotsTxtService.Decision}）：
     * 允许 → 继续；明确禁止 → 终态 {@link CrawlUrl#STATUS_ROBOTS_DENIED}；
     * 暂时读不到 → {@link CrawlFrontier#defer} 推迟 10 分钟再看。
     */
    private boolean checkRobots(CrawlUrl url, CrawlSeed seed) {
        if (seed.isMediaWikiApi()) {
            // MediaWiki 类站点跳过 robots 判定，**这是有意的，且不可改成"照样判一次"**。
            //
            // 维基媒体的 robots.txt 对 User-agent: * 是 Disallow: /w/，
            // 而它的 API 恰好就在 /w/api.php —— 照搬 robots 判定的结果是
            // 这类种子一个请求都发不出去，表现为"加了种子但毫无动静"。
            // 反过来，正文页 /wiki/ 是允许抓的，也就是说对方并不排斥被采集，
            // 它禁止的是 /w/ 下的编辑入口与缓存目录。
            //
            // 对方对 API 的规范是另一套：描述性的 User-Agent + maxlag（我们两个都照做，
            // 见 MediaWikiApiExtractor 的 maxlag 说明）。按对方为该接口指定的规则访问，
            // 比套用一条为网页抓取写的规则更合适。
            //
            // ⚠️ 这个豁免只对 MEDIAWIKI_API 生效。任何其它站点、哪怕是同一个域名，
            // 只要 source_type 是 HTML，就仍然走下面的 robots 判定。
            log.info("种子站 {} 为 API 型来源，按 MediaWiki 的 API 规范访问（描述性 UA + maxlag），跳过 robots 判定",
                    seed.getSeedName());
            return true;
        }
        if (!seed.respectsRobots()) {
            // 种子显式配置了不遵守 robots（应已取得站点授权，见 t_crawl_seed.respect_robots 的注释）。
            // 这里记 WARN 而不是静默跳过：这是一个不该被遗忘的例外配置。
            log.warn("种子站 {} 配置为不遵守 robots.txt，请确认已获得站点授权", seed.getSeedName());
            return true;
        }
        RobotsTxtService.Decision decision =
                robotsTxtService.decide(url.getUrl(), properties.getCrawl().getUserAgent());
        switch (decision) {
            case ALLOWED -> {
                return true;
            }
            case DENIED -> {
                // 终态，不重试：robots 的变化周期以月计，为它保留重试轮次不划算，
                // 而且每次重试都要先拉一遍 robots.txt，是对对方额外的请求。
                crawlFrontier.markTerminal(url, CrawlUrl.STATUS_ROBOTS_DENIED,
                        "robots.txt 明确禁止采集该路径");
                return false;
            }
            case UNAVAILABLE -> {
                // ⭐ 这里**不能**置终态。2026-10-17 之前 isAllowed 把「读不到 robots.txt」
                // 与「对方禁止」并成一个 false，于是：对方服务器 5xx 抖一次、
                // 或 robots.txt 取错了 scheme（旧实现写死 https，http 站点必然失败），
                // 整站 URL 就被打成终态 status=5，再也不会被取出 —— 重启也没用，
                // 因为 enqueue 对已存在的行是幂等的（不会重置状态）。
                // 现象正是「爬虫开了、但一条也不抓」。
                //
                // 推迟而不是原地等待：本方法跑在 worker 线程上，睡在这里会占住
                // 一个域名槽位（见 CrawlRateLimiter 关于「推迟 vs 等待」的说明）。
                crawlFrontier.defer(url, ROBOTS_UNAVAILABLE_DEFER_MILLIS);
                return false;
            }
            default -> {
                // 枚举新增分支时宁可保守：不抓，但也不置终态
                log.warn("未处理的 robots 判定结果 {}，本轮跳过: url={}", decision, url.getUrl());
                crawlFrontier.defer(url, ROBOTS_UNAVAILABLE_DEFER_MILLIS);
                return false;
            }
        }
    }

    /**
     * 取域名限速配额。拿不到就把 URL 推回队列。
     *
     * @return true 表示可以立刻发请求
     */
    private boolean acquireRate(CrawlUrl url, CrawlSeed seed) {
        double qps = seed.effectiveQps(properties.getCrawl().getDefaultQps());
        long waitMillis = crawlRateLimiter.tryAcquireMillis(url.getDomain(), qps);
        if (waitMillis <= 0) {
            return true;
        }
        // 退回队列而不是原地等待，见 CrawlRateLimiter 的类注释
        crawlFrontier.defer(url, waitMillis);
        return false;
    }

    /** 抓取失败的处置：可重试的退避重试，不可恢复的直接跳过。 */
    private void handleFetchFailure(CrawlUrl url, PageFetchResult result) {
        if (result.isPermanentFailure()) {
            // 404 / 403 这类。**不重试** —— 见 PageFetchResult 的说明，
            // 对确定不存在的页面反复重试是「不礼貌爬虫」最典型的特征。
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_SKIPPED,
                    "永久失败，不再重试: " + result.getErrorMessage());
            return;
        }
        int retryCount = url.getRetryCount() == null ? 0 : url.getRetryCount();
        if (retryCount >= properties.getCrawl().getMaxRetry()) {
            // 进死信等人工处理。**不自动删除**：一个 URL 反复失败往往意味着
            // 配置有问题（路径前缀写错、站点改版），需要人看一眼，静默丢弃会掩盖这个问题。
            crawlFrontier.markTerminal(url, CrawlUrl.STATUS_DEAD,
                    "重试 " + retryCount + " 次仍失败，已进死信: " + result.getErrorMessage());
            return;
        }
        crawlFrontier.markRetry(url, result.getHttpStatus(),
                result.getErrorMessage(), result.getRetryAfterSeconds());
    }

    private boolean isInScope(CrawlSeed seed, String url) {
        return url != null
                && urlNormalizer.inScope(url, seed.getDomain(), seed.allowsSubdomain())
                && urlNormalizer.matchPathPrefix(url, seed.getPathPrefix());
    }

    /**
     * 把抓到的页面写进 {@code t_search_content}。
     *
     * <p><b>存的是摘要而不是全文</b>，这是著作权上的硬约束而不是存储优化：
     * 只保留足以让用户判断「要不要点进去」的信息，完整内容留在原站，
     * 详情页跳回原文。把全文镜像进来，性质就从「搜索索引」变成「内容转载」了。
     *
     * <p>{@code publishTime} 刻意留空。<b>这不是遗漏</b>：ES 的 {@code date} 字段一旦显式声明
     * {@code format} 就不再回落到默认解析，而 {@code DocumentConverter} 写入的是
     * {@code LocalDateTime.toString()}（带 {@code T}）。这个格式是否被接受尚无实测结论，
     * 而失败的粒度是<b>整条文档</b>（不是单个字段）—— 也就是说踩中的代价是这批内容全都搜不到。
     * 待 {@code docs/08-roadmap.md} §6.1 的第 1 项实测出结论后再补。
     * 缺失值在 ES 里排在最后（{@code missing: _last}），不会破坏排序。
     */
    private Long persist(CrawlUrl url, CrawlSeed seed, ResponseExtractor.ExtractedContent extracted,
                         String contentHash, List<String> matchedWords) {
        boolean rejected = !matchedWords.isEmpty();
        SearchContent content = SearchContent.builder()
                .typeCode(CrawlAuditService.TYPE_CODE_WEBPAGE)
                // bizId = urlHash：一个 URL 唯一对应一行内容，重采同页天然是 upsert 而不是新增
                .bizId(url.getUrlHash())
                .title(extracted.title())
                .content(buildSummary(extracted))
                // 标签存种子站名，让「按来源站点筛选」在 domain 之外还能有一个更友好的口径
                .tags(seed.getSeedName())
                .bizWeight(authorityWeight(url.getDepth()))
                .status(resolveInitialStatus(rejected))
                .extra(buildExtra(url, extracted, contentHash, matchedWords))
                .build();
        return contentService.save(content);
    }

    /**
     * 一条刚采回来的内容该以什么状态落库。
     *
     * <p>三种取值，判断顺序不能换：
     * <ol>
     *   <li>命中敏感词 → 驳回（{@code status=2}）。<b>这一条与自动发布无关</b>：
     *       自动发布放的是"没被规则拦住的内容"，而不是"所有内容"。</li>
     *   <li>未命中且开了 {@code kuma.crawl.auto-publish} → 直接已发布（{@code status=1}），
     *       走 Outbox 进 ES，立刻能搜到。</li>
     *   <li>未命中但关掉了自动发布 → 待审（{@code status=0}），等人在管理后台放行。</li>
     * </ol>
     *
     * <p>这里返回 1 而不是"落库后再改一次状态"，是因为 {@code ContentService#save} 会在
     * <b>同一个事务</b>里写出 Outbox 事件 —— 分两步做的话，第二步失败就会留下
     * "库里是待审、却没有任何事件"的行，而它永远等不到一个放行动作来补事件。
     */
    private int resolveInitialStatus(boolean rejected) {
        if (rejected) {
            return CrawlAuditService.STATUS_REJECTED;
        }
        return properties.getCrawl().isAutoPublish()
                ? CrawlAuditService.STATUS_PUBLISHED
                : CrawlAuditService.STATUS_PENDING;
    }

    /**
     * 摘要：优先用页面的 meta description，没有就从正文头部截。
     *
     * <p>优先用 description 而不是直接截正文：它是站点自己为这个页面写的概述，
     * 质量几乎总比「正文前 300 字」高（后者常常从「首页 » 栏目 » 正文」这种面包屑开始）。
     * 但 description 也可能长得离谱（有的站点会塞满关键词），因此仍需截断。
     */
    private String buildSummary(ResponseExtractor.ExtractedContent extracted) {
        String description = extracted.description();
        if (description != null && !description.isBlank()) {
            return truncate(description, SUMMARY_MAX_LENGTH);
        }
        return truncate(extracted.text(), SUMMARY_MAX_LENGTH);
    }

    /**
     * 来源权重：距种子入口越近，越可能是站点的正式栏目内容。
     *
     * <p>这是一个启发式而非精确的权威度评估 —— 真正的权威度需要人工给每个种子站打分，
     * 而 {@code t_crawl_seed} 目前没有这样一个字段（见 {@code docs/08-roadmap.md}）。
     * 之所以仍然要给它一个非恒定值：{@code bizWeight} 是 webpage 类型的<b>首要排序键</b>，
     * 全部取同一个值等于这个排序键不存在，结果就是「深层抓到的边角页面与栏目标题并列，
     * 谁在前面取决于主键」。深度至少是个有依据的近似。
     *
     * <p>下限 0.2 而不是继续线性下降：权重降到接近 0 会让深层页面在任何排序下都沉底，
     * 而它们并非没有价值，只是优先级低。
     */
    private float authorityWeight(Integer depth) {
        int d = depth == null ? 0 : depth;
        return Math.max(0.2f, 1.0f - d * 0.1f);
    }

    /**
     * 构造 {@code extra} JSON。
     *
     * <p><b>键名必须是 {@code t_index_type_config.field_config} 里声明过的 camelCase 名</b>
     * （这里是 {@code domain} 与 {@code sourceUrl}），否则 {@code DocumentConverter.toDocument}
     * 会在白名单过滤时把它们丢掉，字段静默地不进索引。
     * 其余键（{@code contentHash} / {@code matchedWords} / …）不在 field_config 中，
     * 会被过滤掉，只用于我们自己的审核与排查 —— 这是有意的：
     * 它们不该出现在检索文档里，也不该被搜索到。
     *
     * <p><b>用 {@link ObjectNode} 而不是手拼字符串。</b>这里原来是手写的，理由写的是
     * 「结构固定、只有字符串与数字两类值，手写 + 显式转义更可见」。那个理由已经被一次
     * 真实缺陷否掉了，值得记下来：手拼时逗号由字符串字段的辅助方法负责（它靠
     * 「builder 里已经有内容吗」来决定要不要加），而 {@code depth} 是<b>单独一句 append</b>、
     * 不参与这套判断 —— 于是<b>每一次</b>写入都在最后一个字符串字段与 {@code depth} 之间
     * 少一个逗号：
     * <pre>{"domain":"…","sourceUrl":"…","contentHash":"…""depth":0}</pre>
     * MySQL 的 JSON 列直接拒绝（{@code Invalid JSON text: "Missing a comma or '}' after an
     * object member."}），采集一条都存不进去，而日志里看着只像「这个页面有点怪」。
     * 只要还是手拼，同类缺陷就会随字段增删再来一次；交给序列化器则少写逗号这件事
     * 在物理上不可能发生。转义同理：{@code extra} 的值全部来自不可控的网页，
     * 而 Jackson 连孤立代理项这类手写转义容易漏掉的情况也是对的。
     */
    private String buildExtra(CrawlUrl url, ResponseExtractor.ExtractedContent extracted,
                              String contentHash, List<String> matchedWords) {
        ObjectNode extra = objectMapper.createObjectNode();
        putIfPresent(extra, "domain", url.getDomain());
        putIfPresent(extra, "sourceUrl", url.getUrl());
        putIfPresent(extra, "contentHash", contentHash);
        putIfPresent(extra, "matchedWords", matchedWords.isEmpty() ? null : String.join(",", matchedWords));
        putIfPresent(extra, "etag", url.getEtag());
        putIfPresent(extra, "canonicalUrl", extracted.canonicalUrl());
        extra.put("depth", url.getDepth() == null ? 0 : url.getDepth());
        // ObjectNode.toString() 不会抛：序列化一个已经建好的树没有失败路径。
        return extra.toString();
    }

    /** 写入一个字符串字段；值为 null 或空时整个字段省略（空串与缺失在此等价）。 */
    private void putIfPresent(ObjectNode node, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        node.put(key, value);
    }

    /**
     * 把本页发现的链接入队，返回真正入队的条数。
     *
     * <p>配额用尽的判定在循环之前，而不是每个链接判一次 —— 这个条件在一次循环里不会变，
     * 放在里面只是白白多做几百次数据库查询。
     *
     * <p>深度上限则<b>不能</b>提到最前面：两类链接的深度不同，见
     * {@link ResponseExtractor.ExtractedContent#sameLevelLinks()} ——
     * 续页与普通链接必须分开判，否则一个 500 条的列表翻到第 5 屏就用光了 {@code max_depth}。
     */
    private int enqueueDiscoveredLinks(CrawlSeed seed, ResponseExtractor.ExtractedContent extracted,
                                       CrawlUrl current) {
        if (seed.isQuotaExhausted(crawlFrontier.countFetched(seed.getId()))) {
            log.debug("种子 {} 页数配额已用尽，不再入队新链接", seed.getSeedName());
            return 0;
        }

        int currentDepth = current.getDepth() == null ? 0 : current.getDepth();
        // 页内去重，两类链接共用一个 seen：同一页里同一个链接出现几十次是常态
        // （导航栏 + 页脚 + 正文），跨列表去重是为了避免同一个 URL 被按两种深度各入队一次。
        Set<String> seen = new HashSet<>();

        // 同层链接（续页）：深度与本页相同，因此不受 childDepth 的约束 ——
        // 一个 max_depth=1 的种子仍然应该能翻完整个列表，列表的"下一屏"不是"更深一层"。
        int enqueued = enqueueAll(seed, extracted.sameLevelLinks(), currentDepth,
                current, seen);

        int childDepth = currentDepth + 1;
        if (childDepth <= seed.effectiveMaxDepth()) {
            enqueued += enqueueAll(seed, extracted.links(), childDepth, current, seen);
        }

        log.debug("从 {} 入队新链接 {} 条（候选 {} + {} 条）", current.getUrl(), enqueued,
                extracted.links().size(), extracted.sameLevelLinks().size());
        return enqueued;
    }

    /**
     * 归一化 → 去重 → 范围判定 → 入队，返回入队成功的条数。
     *
     * @param depth 入队时写入的深度。普通链接是父页深度 +1，同层链接（续页）与父页相同
     */
    private int enqueueAll(CrawlSeed seed, List<String> rawLinks, int depth,
                           CrawlUrl current, Set<String> seen) {
        int enqueued = 0;
        for (String raw : rawLinks) {
            String normalized = urlNormalizer.normalize(raw, current.getUrl());
            if (normalized == null) {
                continue;
            }
            if (!seen.add(normalized)) {
                continue;
            }
            // 范围判定放在归一化之后：归一化会去掉跟踪参数、统一大小写，
            // 先判范围会把一批「归一化后其实在范围内」的链接误杀。
            if (!isInScope(seed, normalized)) {
                continue;
            }
            if (crawlFrontier.enqueue(seed, normalized, current.getUrlHash(), depth)) {
                enqueued++;
            }
        }
        return enqueued;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 正文长度，null 按 0 算。只用于日志与 error_msg，不做判定。 */
    private int length(String s) {
        return s == null ? 0 : s.length();
    }
}
