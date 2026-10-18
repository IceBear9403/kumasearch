package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采集种子站配置，对应 {@code t_crawl_seed}。
 *
 * <p>把「采哪些站」做成一张表而不是配置文件，是为了让加减站点不需要发版 ——
 * 与 {@code t_index_type_config}「新增内容类型只需 INSERT 一行」是同一套思路。
 *
 * <p><b>这张表是可以把采集器变成打内网工具的入口。</b>{@code entryUrl} 与 {@code domain}
 * 由一个管理员填写，若允许填 {@code http://127.0.0.1:8080} 或云元数据地址
 * {@code http://169.254.169.254/}，采集器就成了 SSRF 跳板。
 * 拦截点有两处、都必须保留：本类的 {@link #isEnabled()} 只判启用状态，
 * 真正的地址校验在 {@code CrawlPipeline} 里（受 {@code kuma.crawl.allow-private-hosts} 控制，
 * 该开关在生产必须为 false），且服务端在写入种子站时也做一次。
 *
 * @see com.kuma.search.crawl.CrawlFrontier
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_crawl_seed")
public class CrawlSeed implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 启用。 */
    public static final int STATUS_ENABLED = 1;
    /** 停用。停用后已入队的 URL 不再被取出，但不影响已有内容的检索。 */
    public static final int STATUS_DISABLED = 0;

    /**
     * 来源形态：普通网页（HTML）。
     *
     * <p>抓回来的响应体是 HTML，由 {@code ContentExtractor} 解析正文与出站链接。
     */
    public static final String SOURCE_TYPE_HTML = "HTML";

    /**
     * 来源形态：MediaWiki 的 {@code api.php}。
     *
     * <p>抓回来的响应体是 JSON，由 {@code MediaWikiApiExtractor} 解析。
     * 之所以把这几个维基类站点单列一种形态，而不是继续按 HTML 抓，
     * 理由集中写在 {@code MediaWikiApiExtractor} 的类注释里（robots 的正当性、
     * 摘要质量、枚举的可靠性三条）。
     *
     * <p>⚠️ 与之绑定的一条行为差异：<b>这类种子跳过 robots 判定</b>。
     * 维基媒体的 robots.txt 对 {@code *} 是 {@code Disallow: /w/}，
     * 而我们只用 {@code /w/api.php}（会被这条规则禁止），正文页 {@code /wiki/} 反倒允许。
     * 也就是说照搬 robots 判定会让这类种子一个请求都发不出去，
     * 但对方对 API 的规范是「用描述性的 UA + maxlag」（见 {@code MediaWikiApiExtractor}），
     * 那套规范我们照做。见 {@code CrawlPipeline#checkRobots}。
     */
    public static final String SOURCE_TYPE_MEDIAWIKI_API = "MEDIAWIKI_API";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 种子站名称，仅用于后台展示。 */
    private String seedName;

    /** 入口 URL。采集从这里出发，按 {@link #maxDepth} 向下爬。 */
    private String entryUrl;

    /**
     * 站点域名。
     *
     * <p>与 {@code entryUrl} 分开存而不是每次现解析：出站链接的域名判定是每个页面都要做的
     * 高频操作，现解析既慢又容易因为大小写/端口差异判错。
     */
    private String domain;

    /**
     * 是否允许采集子域名。
     *
     * <p>默认 0（不允许）。放开子域名会把采集范围从「一个站」变成「一个机构的全部站」，
     * 常常远超预期 —— {@code www.x.com} 与 {@code shop.x.com}、{@code bbs.x.com}
     * 是完全不同的内容源，误采的代价是审核队列爆炸。
     */
    private Integer allowSubdomain;

    /**
     * 路径前缀，把采集范围限死在栏目内（如 {@code /news/}）。
     *
     * <p>为空表示全站。对大型站点这是唯一有效的收敛手段 —— 靠 {@link #maxPages} 兜底
     * 意味着会先把宝贵的页数配额花在首页/关于我们/联系方式这类无检索价值的页面上。
     */
    private String pathPrefix;

    /** 距种子的最大层级。深度越大越容易爬到无关内容与死链。 */
    private Integer maxDepth;

    /**
     * 单种子累计页数上限。
     *
     * <p><b>必须有，且不能只靠人自觉。</b>一次「路径前缀忘了填」的误配就足以把一个
     * 百万页的站点整个搬进来，把磁盘和 ES 写满。到量后种子被自动置为
     * {@link #isQuotaExhausted} 状态停采，而不是静默继续。
     */
    private Integer maxPages;

    /**
     * 对本站的请求速率上限（每秒）。
     *
     * <p>礼貌爬取的核心参数。0.5 表示 2 秒一个请求 —— 对目标站是可以忽略的负载。
     * 调高这里不会让采集更快多少（受审核与重抓逻辑限制），只会更快地把自己的 IP 送进黑名单。
     */
    private BigDecimal qps;

    /** 是否遵守 robots.txt。默认 1；置 0 需要留下明确理由（如已取得站点授权）。 */
    private Integer respectRobots;

    /**
     * 来源形态：{@link #SOURCE_TYPE_HTML}（默认）或 {@link #SOURCE_TYPE_MEDIAWIKI_API}。
     *
     * <p>决定 {@code CrawlPipeline} 用哪个抽取器解响应体。做成列而不是靠 URL 特征猜：
     * 猜错的表现是「这一批页面一条都进不来，且没有任何报错」——
     * 而这类静默失败正是本项目反复想避免的。一列字符串换掉一类难排查的故障，值得。
     */
    private String sourceType;

    /** 1 启用 / 0 停用。 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除。与其它表的 {@code (唯一键, deleted)} 组合唯一键用法保持一致。 */
    @TableLogic
    private Integer deleted;

    /** 该种子是否处于启用状态。{@code status} 为 null 时按停用处理（宁可少采，不可误采）。 */
    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }

    /**
     * robots.txt 是否需要遵守。null 按遵守处理，即默认从严。
     *
     * <p><b>⚠️ 这个方法不能叫 {@code isRespectRobots()}。</b> Lombok 的 {@code @Data}
     * 已经为字段 {@link #respectRobots}（{@code Integer}）生成了 {@code getRespectRobots()}，
     * 再补一个返回 {@code boolean} 的 {@code isRespectRobots()}，属性 {@code respectRobots}
     * 就有了两个类型不同的 getter —— JavaBeans 规范不允许，MyBatis 的内省会直接抛：
     *
     * <pre>
     * ReflectionException: Illegal overloaded getter method with ambiguous type
     * for property 'allowSubdomain' in class CrawlSeed
     * </pre>
     *
     * <p>它抛在<b>第一次用这个实体查库时</b>（内省是懒的），因此症状是"采集一动就报错"，
     * 而报错指向的是一个跟查询毫无关系的类。规避方式就是换个名字：
     * 实体上的辅助方法用动词起头（{@code allowsXxx} / {@code respectsXxx}），
     * 别用 {@code isXxx()} 去跟同名字段抢。
     */
    public boolean respectsRobots() {
        return respectRobots == null || respectRobots != 0;
    }

    /** 是否允许子域名。null 按不允许处理。命名理由见 {@link #respectsRobots()}。 */
    public boolean allowsSubdomain() {
        return allowSubdomain != null && allowSubdomain != 0;
    }

    /**
     * 是否走 MediaWiki API。
     *
     * <p>null 与未知取值都按 HTML 处理，也就是<b>默认落在安全的一侧</b>：
     * HTML 路径既做 robots 判定又走通用抽取器，猜错的代价只是这一批页面走不出内容，
     * 而不会绕过 robots 或让 JSON 被当成 HTML 解析出一堆垃圾。
     */
    public boolean isMediaWikiApi() {
        return SOURCE_TYPE_MEDIAWIKI_API.equals(sourceType);
    }

    /** 生效的 QPS：未配置或配成非正数时回落到全局默认，由调用方传入。 */
    public double effectiveQps(double fallback) {
        if (qps == null) {
            return fallback;
        }
        double v = qps.doubleValue();
        return v > 0 ? v : fallback;
    }

    /** 生效的最大深度：未配置或配成负数时按 0（只抓入口页）处理。 */
    public int effectiveMaxDepth() {
        return maxDepth == null || maxDepth < 0 ? 0 : maxDepth;
    }

    /** 生效的页数上限：未配置按 0 处理，表示「未设上限」——调用方需自行决定是否接受。 */
    public int effectiveMaxPages() {
        return maxPages == null || maxPages < 0 ? 0 : maxPages;
    }

    /**
     * 页数配额是否已用尽。
     *
     * <p>页数不是这张表的字段（未在表上做自增计数器），而是由 {@code CrawlFrontier}
     * 实时 {@code COUNT} 出来的：把计数落在表上会引入一个必须与真实抓取量保持一致
     * 的冗余字段，一次失败的回滚就能让它永久跑偏，而 COUNT 走索引并不慢。
     */
    public boolean isQuotaExhausted(int currentPageCount) {
        int limit = effectiveMaxPages();
        return limit > 0 && currentPageCount >= limit;
    }
}
