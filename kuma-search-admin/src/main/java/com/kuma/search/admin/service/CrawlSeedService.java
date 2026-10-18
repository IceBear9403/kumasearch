package com.kuma.search.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.crawl.CrawlAuditService;
import com.kuma.search.repository.entity.CrawlSeed;
import com.kuma.search.repository.entity.CrawlUrl;
import com.kuma.search.repository.mapper.CrawlSeedMapper;
import com.kuma.search.repository.mapper.CrawlUrlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 采集种子站的运维。
 *
 * <h3>⚠️ 这个类是 SSRF 的入口，写入校验必须在这里做</h3>
 * 一个管理员可以在 {@code entry_url} 里填任意地址，而采集器会去请求它。
 * 填 {@code http://127.0.0.1:8080/} 就是拿采集器当内网扫描器，
 * 填 {@code http://169.254.169.254/} 就是读云厂商的元数据服务（里面有临时凭据）。
 *
 * <p>项目的拦截点是<b>两层，都要保留</b>：
 * <ol>
 *   <li>这里 —— 写入时校验，让错误配置在最接近人的地方被拒绝，管理员当场看到原因；</li>
 *   <li>{@code HttpPageFetcher#isHostAllowed} —— 抓取时再校验一次，
 *       由 {@code kuma.crawl.allow-private-hosts}（生产必须 false）控制。
 *       第二层不是冗余：种子站是数据，而数据可以被直接改库、被历史遗留数据带入、
 *       被一个绕过本接口的脚本写入，那时只有第二层还在。</li>
 * </ol>
 *
 * <h3>停用而不是删除</h3>
 * 删除一个种子站不会删掉它已经采到的内容与都队列里的 URL ——
 * 那些 URL 的 {@code seed_id} 会变成悬空引用，队列里从此留下一批没有归属、
 * 再也没有人会去处理的任务。因此删除是<b>逻辑删除 + 队列清理</b>两部分，
 * 见 {@link #delete}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlSeedService {

    /**
     * 允许写入的 {@code source_type} 取值。
     *
     * <p>与 {@code CrawlSeed} 上的常量是同一个清单 —— 之所以不直接引用那三个常量拼集合，
     * 是因为这里要的是"能被接受的全部取值"，而常量是用来做判断的；
     * 两者混在一起后，加一种新来源时容易只改了一边。
     * 新增来源形态时，<b>这里与 {@code CrawlSeed} 的常量必须同时改</b>（错误信息里会列出本集合）。
     */
    private static final Set<String> SUPPORTED_SOURCE_TYPES =
            Set.of(CrawlSeed.SOURCE_TYPE_HTML, CrawlSeed.SOURCE_TYPE_MEDIAWIKI_API);

    private final CrawlSeedMapper crawlSeedMapper;
    private final CrawlUrlMapper crawlUrlMapper;
    private final KumaSearchProperties properties;

    /** 列出全部种子站（含停用）。数量以「个」计，不需要分页。 */
    public List<CrawlSeed> list() {
        return crawlSeedMapper.selectList(
                new LambdaQueryWrapper<CrawlSeed>().orderByDesc(CrawlSeed::getUpdateTime));
    }

    /**
     * 新建种子站。
     *
     * <p>默认值都是「从严」（不采子域、遵守 robots、低频、页数有上限）：
     * 宽松的默认值会在管理员没注意时把一个站整个搬进来，
     * 而从严的默认值最多是"采得比预期少一点"，后者是可接受的。
     */
    public Long create(CrawlSeed seed) {
        validate(seed);
        if (seed.getStatus() == null) {
            seed.setStatus(CrawlSeed.STATUS_DISABLED);
        }
        if (seed.getAllowSubdomain() == null) {
            seed.setAllowSubdomain(0);
        }
        if (seed.getRespectRobots() == null) {
            seed.setRespectRobots(1);
        }
        if (seed.getMaxDepth() == null) {
            seed.setMaxDepth(3);
        }
        if (seed.getQps() == null) {
            seed.setQps(BigDecimal.valueOf(properties.getCrawl().getDefaultQps()));
        }
        crawlSeedMapper.insert(seed);
        log.info("新建采集种子站: id={} domain={} status={}", seed.getId(), seed.getDomain(), seed.getStatus());
        return seed.getId();
    }

    /**
     * 更新种子站。
     *
     * <p>坑：修改 {@code entry_url} 或 {@code domain} <b>不会</b>清空已有队列。
     * 旧域名的待抓 URL 仍会按原计划被抓完（它们的 {@code domain} 字段是独立的），
     * 只是不会再产生新的。这是刻意的：直接清队列会让一次误改不可撤销，
     * 而"多抓几个已经排队的页面"是可接受的。真要停采，把 {@code status} 置 0。
     */
    public void update(CrawlSeed seed) {
        if (seed.getId() == null) {
            throw BizException.paramInvalid("id 不能为空");
        }
        validate(seed);
        crawlSeedMapper.updateById(seed);
        log.info("更新采集种子站: id={} domain={}", seed.getId(), seed.getDomain());
    }

    /**
     * 只改启用状态。
     *
     * <p><b>必须是独立方法，不能复用 {@link #update}。</b>{@code update} 会跑
     * {@link #validate} 并据此认为提交的是完整配置 —— 而一个{"id":1,"status":0}
     * 的补丁里没有 entryUrl，会被判成"entryUrl 不能为空"而拒绝。
     * 更隐蔽的是反过来：若为了绕过校验而放宽它，一次只改状态的调用就可能在
     * 别的字段上提交了空值，把完整配置冲掉一部分。
     *
     * <p>这里只写一个字段，用 MyBatis-Plus 的 {@code updateById}（null 字段不参与
     * SET 子句）而不是整行覆盖，因此并发修改其它字段不会被这次调用冲掉。
     */
    public void changeStatus(Long id, Integer status) {
        CrawlSeed existing = crawlSeedMapper.selectById(id);
        if (existing == null) {
            throw BizException.notFound("种子站不存在: " + id);
        }
        int target = status == null ? CrawlSeed.STATUS_DISABLED : status;
        if (target != CrawlSeed.STATUS_ENABLED && target != CrawlSeed.STATUS_DISABLED) {
            throw BizException.paramInvalid("status 只能是 1（启用）或 0（停用）");
        }

        CrawlSeed patch = new CrawlSeed();
        patch.setId(id);
        patch.setStatus(target);
        crawlSeedMapper.updateById(patch);
        log.info("采集种子站状态变更: id={} domain={} status={}",
                id, existing.getDomain(), target);
    }

    /**
     * 删除种子站。
     *
     * <p><b>两步，且必须在同一个事务里</b>：先把队列里属于它的待抓 URL 置为「已跳过」，
     * 再逻辑删除种子站。只删种子站的话，那些 URL 的 {@code seed_id} 就成了悬空引用 ——
     * 它们仍会被调度器取出（队列不查种子站是否存在），抓完写进内容表，
     * 而这些内容对应的站点已经从白名单里消失了。那种内容谁来审核、按什么标准审，
     * 是一笔说不清的账。
     *
     * <p>置为「已跳过」而不是物理删除：队列的 {@code url_hash} 是
     * {@code t_search_content.biz_id}，删掉队列行会让"这个 URL 当初为什么没被抓"
     * 永远无法回答。已抓完（status=2）的行不动，它们对应的是已入库的内容。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CrawlSeed seed = crawlSeedMapper.selectById(id);
        if (seed == null) {
            throw BizException.notFound("种子站不存在: " + id);
        }

        List<CrawlUrl> leftover = crawlUrlMapper.selectList(new LambdaQueryWrapper<CrawlUrl>()
                .eq(CrawlUrl::getSeedId, id)
                .in(CrawlUrl::getStatus,
                        CrawlUrl.STATUS_PENDING,
                        CrawlUrl.STATUS_FETCHING,
                        CrawlUrl.STATUS_RETRY));
        for (CrawlUrl url : leftover) {
            CrawlUrl patch = new CrawlUrl();
            patch.setId(url.getId());
            patch.setStatus(CrawlUrl.STATUS_SKIPPED);
            patch.setErrorMsg("种子站已删除，任务作废");
            crawlUrlMapper.updateById(patch);
        }

        crawlSeedMapper.deleteById(id);
        log.warn("删除采集种子站: id={} domain={} 作废队列任务数={}", id, seed.getDomain(), leftover.size());
    }

    /**
     * 队列概览，供后台展示「这个站还有多少没抓」。
     *
     * <p>按域名逐个 {@code COUNT} 而不是一条 {@code GROUP BY}：种子站以「个」计
     * （通常是几十个），几十次走索引的 COUNT 在后台的一次请求里完全可以接受，
     * 而它换来的是不需要在 Mapper 里多一条只服务于展示的 SQL。
     * 种子站涨到几百个以上时再改成聚合查询。
     */
    public List<Map<String, Object>> queueOverview() {
        List<CrawlSeed> seeds = list();
        List<Map<String, Object>> result = new ArrayList<>(seeds.size());
        for (CrawlSeed seed : seeds) {
            int pending = StringUtils.hasText(seed.getDomain())
                    ? crawlUrlMapper.countPendingByDomain(seed.getDomain())
                    : 0;
            int fetched = crawlUrlMapper.countFetchedBySeed(seed.getId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seedId", seed.getId());
            row.put("seedName", seed.getSeedName());
            row.put("domain", seed.getDomain());
            row.put("status", seed.getStatus());
            row.put("pending", pending);
            row.put("fetched", fetched);
            row.put("maxPages", seed.effectiveMaxPages());
            // 配额是否已用尽由服务端算好：前端各算一遍就会出现"后台显示还能采、
            // 调度器已经停了"这种两边不一致的情况。
            row.put("quotaExhausted", seed.isQuotaExhausted(fetched));
            result.add(row);
        }
        return result;
    }

    /**
     * 校验写入的种子站。
     *
     * <p>SSRF 相关的校验见类注释。除此之外还强制几件事：
     * <ul>
     *   <li>{@code entry_url} 必须是 http/https —— {@code file://}、{@code ftp://}
     *       在"采集网页"的语境下没有意义，而它们会走到完全不同的代码路径；</li>
     *   <li>{@code domain} 必须能从 {@code entry_url} 推导或本身就匹配，
     *       否则会出现"入口在 A 站、域名写 B 站"的配置，结果是采集器在 A 站上
     *       用 B 站的域名做范围判定 —— 表现为"页面抓到了但一个链接都没跟进去"，
     *       排查起来要翻三层日志；</li>
     *   <li>{@code path_prefix} 若填，必须以 {@code /} 开头；
     *       且必须覆盖入口 URL 的路径，见 {@link #validatePathPrefixMatchesEntry}；
     *       另外 {@code source_type} 的取值与入口形态也要自洽，见 {@link #validateSourceType}。</li>
     * </ul>
     * 这几条的共同点是：违规的配置<b>不会报错，只会静默地一条都采不到</b>。
     * 因此宁可在这里拒绝，也不放它进库。
     */
    private void validate(CrawlSeed seed) {
        if (!StringUtils.hasText(seed.getEntryUrl())) {
            throw BizException.paramInvalid("entryUrl 不能为空");
        }
        URI uri = parseUri(seed.getEntryUrl());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw BizException.paramInvalid("entryUrl 只支持 http/https");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw BizException.paramInvalid("entryUrl 缺少主机名");
        }
        rejectPrivateHost(host);

        // domain 未填时用 entryUrl 的主机名补齐，减少一处可以填错的字段
        if (!StringUtils.hasText(seed.getDomain())) {
            seed.setDomain(host.toLowerCase(Locale.ROOT));
        } else {
            String declared = seed.getDomain().trim().toLowerCase(Locale.ROOT);
            seed.setDomain(declared);
            if (!host.equalsIgnoreCase(declared) && !host.toLowerCase(Locale.ROOT).endsWith("." + declared)) {
                throw BizException.paramInvalid(
                        "domain 与 entryUrl 的主机名不匹配：entryUrl=" + host + "，domain=" + declared);
            }
        }

        if (StringUtils.hasText(seed.getPathPrefix()) && !seed.getPathPrefix().startsWith("/")) {
            throw BizException.paramInvalid("pathPrefix 必须以 / 开头");
        }

        validateSourceType(seed, uri);
        validatePathPrefixMatchesEntry(seed, uri);


        // 页数上限：允许为空（表示不限），但不允许填 0 或负数 —— 那等同于"配了一个上限"
        // 却永远不会触发，管理员以为限住了其实没有。
        if (seed.getMaxPages() != null && seed.getMaxPages() <= 0) {
            throw BizException.paramInvalid("maxPages 必须为正数，或留空表示不限");
        }
        if (seed.getQps() != null && seed.getQps().signum() <= 0) {
            throw BizException.paramInvalid("qps 必须为正数");
        }
    }

    /**
     * 校验并归一化 {@code source_type}。
     *
     * <p>留空按 {@link CrawlSeed#SOURCE_TYPE_HTML} 补齐 —— 与 {@link #create} 里其它
     * "从严默认"一致，落在最安全的一侧。
     *
     * <p>取值必须在白名单内，且这里<b>必须拒绝而不是回落到 HTML</b>：
     * 写错一个字母（{@code mediawiki_api} 小写、{@code MEDIAWIKI-API} 用了连字符）
     * 如果只是回落，后果是拿 HTML 抽取器去解一份 JSON ——
     * 抽不出标题、整批 URL 静默地一条都进不来，而日志里只有 DEBUG。
     * 一个当场报错的表单，比事后翻三层日志去猜要便宜得多。
     *
     * <p>另外对 API 型补一条硬约束：入口必须是 {@code api.php}。
     * 抽取器要靠入口的路径反推 API 端点（见 {@code MediaWikiApiExtractor#apiBase}），
     * 填成 {@code /wiki/北京} 会让所有派生出来的请求地址都是错的，
     * 而错误地址的表现同样是"一条都没采到"。
     */
    private void validateSourceType(CrawlSeed seed, URI uri) {
        if (!StringUtils.hasText(seed.getSourceType())) {
            seed.setSourceType(CrawlSeed.SOURCE_TYPE_HTML);
            return;
        }
        String type = seed.getSourceType().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SOURCE_TYPES.contains(type)) {
            throw BizException.paramInvalid("sourceType 只支持 " + SUPPORTED_SOURCE_TYPES
                    + "，当前值：" + seed.getSourceType());
        }
        seed.setSourceType(type);

        if (CrawlSeed.SOURCE_TYPE_MEDIAWIKI_API.equals(type)) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (!path.endsWith("/api.php") && !path.equals("/api.php")) {
                throw BizException.paramInvalid(
                        "sourceType=MEDIAWIKI_API 时 entryUrl 必须是 API 端点（以 /api.php 结尾），"
                                + "如 https://zh.wikipedia.org/w/api.php。当前值：" + seed.getEntryUrl());
            }
        }
    }

    /**
     * {@code path_prefix} 必须能覆盖入口 URL 的路径。
     *
     * <p>覆盖不到就是<b>永远采不到任何东西</b>的配置：唯一会被入队的入口 URL
     * 自己就不在范围内，{@code CrawlPipeline#scopeRejectReason} 会立刻把它置为「已跳过」。
     * 现象是"这个种子加上了但毫无动静"，而不会报任何错 ——
     * 所以在这里拦下来，让管理员当场看到原因。
     *
     * <p>对 API 型尤其重要：它派生出的<b>所有</b>请求地址都在入口所在的目录下
     * （{@code /w/api.php}），所以 {@code path_prefix} 只能是空或 {@code /w/} 之类的前缀。
     * 填成 {@code /wiki/} 是把"网页路径"的想法套到了 API 上，
     * 这是最容易犯、也最难自己发现的一种错。
     */
    private void validatePathPrefixMatchesEntry(CrawlSeed seed, URI uri) {
        if (!StringUtils.hasText(seed.getPathPrefix())) {
            return;
        }
        String prefix = seed.getPathPrefix();
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (!path.startsWith(prefix)) {
            throw BizException.paramInvalid("pathPrefix 覆盖不到入口 URL 的路径，该种子将永远采不到任何页面："
                    + "pathPrefix=" + prefix + "，entryUrl 路径=" + path);
        }
    }

    private URI parseUri(String raw) {
        try {
            return new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw BizException.paramInvalid("entryUrl 不是合法的 URL");
        }
    }

    /**
     * 拒绝内网、环回与云元数据地址。
     *
     * <p>这里只做<b>字面量</b>判定（主机名是 {@code localhost}、
     * 是环回/私有网段的 IPv4 字面量等）。域名解析到内网 IP 的情况
     * （DNS rebinding 与"内网域名"）在这里拦不住 —— 那种必须靠抓取时
     * 对<b>解析后的地址</b>再判一次，也就是 {@code HttpPageFetcher#isHostAllowed}。
     * 两层各管一半，这也是两层都要保留的原因之一。
     */
    private void rejectPrivateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || "::1".equals(h) || "[::1]".equals(h)) {
            throw BizException.paramInvalid("种子站不允许指向本机地址（SSRF 防护）");
        }
        if (isPrivateIpv4(h)) {
            throw BizException.paramInvalid(
                    "种子站不允许指向内网地址（SSRF 防护）。若确需在测试环境采内网，"
                            + "请通过 kuma.crawl.allow-private-hosts 显式开启，并确保该配置不会进入生产");
        }
    }

    private boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        int first = octets[0];
        int second = octets[1];
        // 10/8、172.16/12、192.168/16、127/8、169.254/16（含云元数据 169.254.169.254）、100.64/10（运营商 NAT）
        if (first == 10 || first == 127 || first == 0) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        return first == 100 && second >= 64 && second <= 127;
    }
}
