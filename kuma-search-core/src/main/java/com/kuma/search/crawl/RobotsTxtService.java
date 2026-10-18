package com.kuma.search.crawl;

import com.kuma.search.config.KumaSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * robots.txt 的抓取、缓存与判定。
 *
 * <p><b>这不是一个「可选的优化」。</b>域名已备案、对外提供服务，遵守 robots.txt 是
 * 采集行为合法性的基础前提之一（见 {@code docs/01-requirements.md} 与
 * {@code docs/08-roadmap.md} 的法务条目）。判定不通过时 {@code CrawlPipeline}
 * 会把 URL 置为 {@code CrawlUrl.STATUS_ROBOTS_DENIED} 并<b>不再重试</b>。
 *
 * <h3>判定规则（按 Google 的 robots.txt 解释，也是事实标准）</h3>
 * <ol>
 *   <li>找到与自己 User-Agent 产品标识匹配的 {@code User-agent} 分组；
 *       没有精确匹配就用 {@code *} 分组；都没有则视为全部允许。</li>
 *   <li>在该分组内逐条比对本 URL 的路径：命中多条时<b>取匹配前缀最长的那条</b>
 *       （{@code Disallow: /a/b} 比 {@code Allow: /} 更具体，因此前者生效）。</li>
 *   <li>前缀长度相同时 {@code Allow} 胜出（Google 的约定）。</li>
 * </ol>
 *
 * <p>第 2 条是最容易写错的地方。很多实现写成「先看有没有 Disallow 命中，
 * 有就拒绝」，这在 {@code Allow: /public/} + {@code Disallow: /} 这种常见配置下
 * 会把对方明确开放的区域也拒掉 —— 表现为「这个站明明允许采集，我们却一个页面都没采到」。
 *
 * <h3>robots.txt 拿不到时怎么办</h3>
 * <table border="1">
 *   <caption>失败情形的处置</caption>
 *   <tr><th>情形</th><th>处置</th><th>理由</th></tr>
 *   <tr><td>404 / 410 / 其它 4xx</td><td><b>全部允许</b></td>
 *       <td>按规范，没有 robots.txt 就等于没有限制。这也是绝大多数站点的实际情况。</td></tr>
 *   <tr><td>5xx / 网络失败</td><td><b>临时禁采</b>（短 TTL 标记）</td>
 *       <td>「读不到规则」不等于「没有规则」。此时放行等于赌对方的 robots.txt 是空的，
 *           而赌错的代价是违规采集。宁可这次不采。</td></tr>
 * </table>
 * 两种失败必须用不同的键区分（{@link CrawlKeys#robotsUnavailable}），
 * 否则会把「对方服务器 502 了」当成「对方允许一切」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RobotsTxtService {

    /** 我们的 User-Agent 产品标识。从配置的 UA 里抽第一个词，保证「声明什么就按什么匹配」。 */
    private static final String GROUP_WILDCARD = "*";

    private final KumaSearchProperties properties;
    private final PageFetcher pageFetcher;
    private final StringRedisTemplate redisTemplate;

    /**
     * 5xx / 网络失败时的禁用时长（秒）。
     *
     * <p>固定 10 分钟而不是用 {@code kuma.crawl.robots-cache-seconds}：
     * 对方服务器抖动通常在分钟级恢复，而配置的缓存时长是给正常情况下用的（默认 1 小时）。
     * 用一个短得多的固定值，让恢复更快被察觉到。
     */
    private static final int UNAVAILABLE_TTL_SECONDS = 600;

    /**
     * robots.txt 判定的三种结果。
     *
     * <h3>为什么必须是三种，而不是一个 boolean</h3>
     * 「对方明确禁止」与「这次没读到 robots.txt」的处置完全不同：
     * <ul>
     *   <li>明确禁止 → 终态，永不重试（规则的变化周期以月计）；</li>
     *   <li>没读到（5xx / 网络 / TLS 失败）→ <b>暂时</b>不采，过一会儿再看。</li>
     * </ul>
     * 2026-10-17 之前这里返回 boolean，调用方把 false 一律当成「禁止」并<b>置为终态</b>：
     * 对方服务器抖一下、或者 robots.txt 取错了 scheme，整站的 URL 就会被永久判死，
     * 队列里只剩一串 {@code status=5}，再也不会被取出 —— 现象就是
     * 「爬虫开了、但一条也不抓，而且重启也没用」（{@code enqueue} 不会复活已存在的行）。
     *
     * <p>三种结果与处置的对应关系写在 {@code CrawlPipeline#checkRobots}。
     */
    public enum Decision {
        /** 允许采集。 */
        ALLOWED,
        /** 对方 robots.txt 明确禁止该路径。终态，不重试。 */
        DENIED,
        /** 这次读不到 robots.txt（5xx / 网络失败）。暂时禁采，稍后重试。 */
        UNAVAILABLE
    }

    /**
     * 判定某个 URL 的采集许可。
     *
     * @param url       归一化后的绝对 URL
     * @param userAgent 本次采集用的 User-Agent（用于匹配分组）
     * @return 三态判定结果，见 {@link Decision}
     */
    public Decision decide(String url, String userAgent) {
        String domain = hostOf(url);
        if (domain == null) {
            // URL 本身不成立（没有 host）—— 不是「暂时读不到」，是永远不该抓
            return Decision.DENIED;
        }

        // 先看「robots 不可用」标记。它必须先查：缓存里可能同时存在上一次的 robots 原文
        // （TTL 还没到）与这次的不可用标记，此时应当以「刚才拿不到」为准。
        if (Boolean.TRUE.equals(redisTemplate.hasKey(CrawlKeys.robotsUnavailable(domain)))) {
            log.debug("robots.txt 暂不可用，本轮禁采（稍后重试）: domain={} url={}", domain, url);
            return Decision.UNAVAILABLE;
        }

        String robotsTxt = loadRobots(url, domain);
        if (robotsTxt == null) {
            // loadRobots 返回 null 时已经写好了「不可用」标记，本次直接禁采，不再多查一次 Redis
            return Decision.UNAVAILABLE;
        }
        if (robotsTxt.isEmpty()) {
            // 空字符串是「确实没有 robots.txt」的标记，与 null 语义不同
            return Decision.ALLOWED;
        }
        return evaluate(robotsTxt, pathOf(url), productToken(userAgent))
                ? Decision.ALLOWED : Decision.DENIED;
    }

    /**
     * 判定某个 URL 是否被允许采集。
     *
     * <p>只回答「允许 / 不允许」，<b>不能区分「禁止」与「暂时读不到」</b> ——
     * 需要区分处置方式的调用方请直接用 {@link #decide}。
     *
     * @param url       归一化后的绝对 URL
     * @param userAgent 本次采集用的 User-Agent（用于匹配分组）
     * @return true 表示允许
     */
    public boolean isAllowed(String url, String userAgent) {
        return decide(url, userAgent) == Decision.ALLOWED;
    }

    /**
     * 取 robots.txt 原文。
     *
     * <h3>⚠️ 必须按待抓 URL 的 scheme 取，不能写死 https</h3>
     * 这里原先固定拼 {@code https://<domain>/robots.txt}。对只提供 http 的站点，
     * 这个请求必然失败（连接被拒 / TLS 握手失败 / 端口不通），于是被当成「暂时不可用」
     * —— 再叠加旧的三态缺失（见 {@link Decision}），整站被判成 robots 禁止且不再重试。
     * 而「按原站点的 scheme 去问」本身就是 robots 协议的意思：
     * robots.txt 是某个 origin（scheme + host + port）的规则，不是域名的规则。
     *
     * @return 原文；<b>空字符串</b>表示该域名确实没有 robots.txt（允许采集）；
     *         <b>null</b>表示这次拿不到（已写入不可用标记，调用方应按暂时禁采处理）
     */
    private String loadRobots(String url, String domain) {
        int ttl = properties.getCrawl().getRobotsCacheSeconds();
        String key = CrawlKeys.robots(domain);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }

        String robotsUrl = robotsUrlOf(url, domain);
        PageFetchResult result = pageFetcher.fetch(robotsUrl, null, null);
        String content;
        if (result.isSuccess()) {
            content = result.getBody();
        } else if (result.getHttpStatus() >= 400 && result.getHttpStatus() < 500) {
            // 4xx：没有 robots.txt，等于没有限制。缓存空串而不是不缓存 ——
            // 不缓存的话每个 URL 都要重发一次注定 404 的请求。
            content = "";
        } else {
            // 5xx 或没拿到响应。「读不到」与「没有」必须分开，见类注释。
            log.warn("robots.txt 暂不可用，本轮禁采该域名: domain={} status={} err={}",
                    domain, result.getHttpStatus(), result.getErrorMessage());
            redisTemplate.opsForValue().set(CrawlKeys.robotsUnavailable(domain), "1",
                    Duration.ofSeconds(UNAVAILABLE_TTL_SECONDS));
            return null;
        }

        redisTemplate.opsForValue().set(key, content, Duration.ofSeconds(ttl));
        return content;
    }

    /**
     * 构造该 URL 所属 origin 的 robots.txt 地址。
     *
     * <p>scheme 取自待抓 URL 本身，回落到 https 只用于「URL 里没有 scheme」这种不该出现的情况
     * （归一化后的 URL 一定有 scheme）；端口刻意不带 —— robots.txt 在标准端口上的位置是固定的，
     * 而带端口的站点极少见。宁可对这类站点按默认端口取一次，也不要在这里引入
     * 「端口拼接规则」这种只在一处出现的分支。
     */
    private String robotsUrlOf(String url, String domain) {
        String scheme = "https";
        try {
            String raw = java.net.URI.create(url).getScheme();
            if (raw != null && !raw.isBlank()) {
                scheme = raw.toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException e) {
            log.debug("无法从 URL 取 scheme，robots.txt 按 https 尝试: url={}", url);
        }
        return scheme + "://" + domain + "/robots.txt";
    }

    /**
     * 按 robots.txt 内容判定路径是否允许。
     *
     * <p>独立成公开方法是为了能单测 —— 这个方法的输入输出都是纯文本，
     * 不需要 Redis 或网络，应当被大量用例覆盖（见 {@code docs/08-roadmap.md} 的测试条目）。
     *
     * @param robotsTxt robots.txt 原文
     * @param path      待判定的路径（含查询串无意义，只比对路径部分）
     * @param agent     我们的产品标识，如 {@code KumaSearchBot}
     */
    public boolean evaluate(String robotsTxt, String path, String agent) {
        Map<String, List<Rule>> groups = parse(robotsTxt);
        if (groups.isEmpty()) {
            // 一个有效分组都没有（文件是空的或全是注释）→ 没有限制
            return true;
        }

        List<Rule> rules = groups.get(agent.toLowerCase(Locale.ROOT));
        if (rules == null) {
            rules = groups.get(GROUP_WILDCARD);
        }
        if (rules == null || rules.isEmpty()) {
            return true;
        }

        // 取「匹配前缀最长」的规则；长度相同时 Allow 胜出（Google 约定）
        Rule matched = null;
        for (Rule rule : rules) {
            if (!path.startsWith(rule.path)) {
                continue;
            }
            if (matched == null
                    || rule.path.length() > matched.path.length()
                    || (rule.path.length() == matched.path.length() && rule.allow)) {
                matched = rule;
            }
        }
        return matched == null || matched.allow;
    }

    /**
     * 解析 robots.txt 为「分组 → 规则列表」。
     *
     * <p>关键点：<b>多条连续的 {@code User-agent} 行共享同一组规则</b>
     * （{@code User-agent: a} / {@code User-agent: b} / {@code Disallow: /x} 表示
     * 两条规则都适用于 a 和 b）。写成「每个 User-agent 开一个新分组」会让
     * a 的那组变成空的，表现为「明明写了规则却判定为允许」—— 一条静默的漏判。
     */
    public Map<String, List<Rule>> parse(String robotsTxt) {
        Map<String, List<Rule>> groups = new HashMap<>();
        List<String> currentAgents = new ArrayList<>();
        boolean sawRuleInCurrentBlock = false;

        for (String rawLine : robotsTxt.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                continue;
            }

            switch (field) {
                case "user-agent" -> {
                    if (sawRuleInCurrentBlock) {
                        // 遇到新分组的开始：前一组已经有过规则了，从这里断开
                        currentAgents = new ArrayList<>();
                        sawRuleInCurrentBlock = false;
                    }
                    currentAgents.add(value.toLowerCase(Locale.ROOT));
                }
                case "disallow", "allow" -> {
                    if (currentAgents.isEmpty()) {
                        continue;
                    }
                    // "Disallow:" 后面为空表示允许全部 —— 这条规则不产生任何约束，
                    // 存进规则表反而会干扰「最长匹配」的判定（空前缀会匹配所有路径）
                    if (value.isEmpty()) {
                        sawRuleInCurrentBlock = true;
                        continue;
                    }
                    boolean allow = field.equals("allow");
                    for (String agent : currentAgents) {
                        groups.computeIfAbsent(agent, k -> new ArrayList<>())
                                .add(new Rule(value, allow));
                    }
                    sawRuleInCurrentBlock = true;
                }
                default -> {
                    // Crawl-delay / Sitemap / Host 等字段不参与是否允许的判定。
                    // Crawl-delay 由 CrawlRateLimiter 处理（见那里的说明：它不在这里读，
                    // 因为限速需要按域名聚合，而这里只有单个 URL 的上下文）。
                    if (!currentAgents.isEmpty()) {
                        sawRuleInCurrentBlock = true;
                    }
                }
            }
        }
        return groups;
    }

    /** 去掉行内注释。{@code #} 之后的内容按规范不属于规则。 */
    private String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /**
     * 从 User-Agent 里抽产品标识，用于匹配 robots.txt 的 {@code User-agent} 分组。
     *
     * <p>取第一个 {@code /} 之前的部分（{@code KumaSearchBot/1.0 (+https://…) } → {@code KumaSearchBot}）。
     * robots.txt 的分组匹配按产品标识做，把整个 UA 串拿去比永远匹配不上。
     */
    public String productToken(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return GROUP_WILDCARD;
        }
        String token = userAgent.trim();
        int slash = token.indexOf('/');
        if (slash > 0) {
            token = token.substring(0, slash);
        }
        int space = token.indexOf(' ');
        if (space > 0) {
            token = token.substring(0, space);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    /** 从绝对 URL 里取 host，失败返回 null。 */
    private String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 从绝对 URL 里取路径，空则为 {@code /}。 */
    private String pathOf(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }

    /** 一条 robots 规则。 */
    public record Rule(String path, boolean allow) {
    }
}
