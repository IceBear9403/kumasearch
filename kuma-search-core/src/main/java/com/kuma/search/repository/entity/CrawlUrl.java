package com.kuma.search.repository.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 采集 URL 队列与状态机，对应 {@code t_crawl_url}。
 *
 * <p>这张表同时承担三件事，理解它们的耦合关系才能看懂索引为什么这么建：
 * <ol>
 *   <li><b>去重</b> —— {@link #urlHash} 是归一化后 URL 的 MD5，唯一键在这里。
 *       没有归一化，同一个页面会以 {@code ?utm_source=x}、{@code #anchor}、
 *       大小写不同的 host 等几十种形式入队，重复采集会把页数配额白白烧光。
 *       它同时是 {@code t_search_content.biz_id} —— 一个 URL 唯一对应一行内容，
 *       这让「重新采集同一个页面」天然是一次 upsert 而不是新增。</li>
 *   <li><b>队列</b> —— {@link #status} + {@link #nextFetchTime} 构成待抓队列，
 *       取任务走 {@code idx_status_next}。</li>
 *   <li><b>增量</b> —— {@link #etag} / {@link #lastModified} 支撑条件请求，
 *       {@link #contentHash} 用于判断正文是否真的变了。</li>
 * </ol>
 *
 * <p><b>{@link #contentHash} 是省掉最多重复工作的一列</b>：页面重新抓下来但正文没变时，
 * 直接跳过「写 ES」与「重新送审」两步。列表页、导航页这类内容几乎不变而链接常变的页面，
 * 每次重抓都会命中这个分支 —— 没有它，采集器会把绝大部分算力花在把同样的内容再写一遍上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_crawl_url")
public class CrawlUrl implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待抓。新发现的 URL 都以这个状态入队。 */
    public static final int STATUS_PENDING = 0;
    /** 抓取中。配合 {@link #lockedAt} 作为租约，进程崩溃后由调度器超时回收为待抓。 */
    public static final int STATUS_FETCHING = 1;
    /** 已抓取并已落内容。终态。 */
    public static final int STATUS_DONE = 2;
    /** 抓取失败待重试。下一轮时间见 {@link #nextFetchTime}。 */
    public static final int STATUS_RETRY = 3;
    /** 死信：重试次数耗尽或遇到不可恢复的错误（如域名已注销）。需人工介入，不再自动重试。 */
    public static final int STATUS_DEAD = 4;
    /** robots.txt 禁止。**终态，不重试** —— robots 的变化周期以月计，为它保留重试轮次不划算。 */
    public static final int STATUS_ROBOTS_DENIED = 5;
    /**
     * 已跳过：抓了也不入库，或按规则不该再抓。**终态，不重试**。
     *
     * <p>这是一个<b>多种原因共用</b>的终态，具体原因看 {@code error_msg}，涵盖：
     * 非文本内容（图片/PDF/压缩包）、超出范围（域名/子域/路径前缀/深度）、
     * 正文过短或为空、抽取失败（疑似 JS 渲染页或拦截页）、种子站已停用，
     * 以及 404/403 这类永久失败（对确定不存在的页面反复重试是"不礼貌爬虫"最典型的特征）。
     *
     * <p>不为每种原因单开一个状态值，是因为状态机的分支越多，取任务与统计的写法就越容易漏；
     * 状态码只需回答"还要不要再抓"，而"为什么"是人要看的信息，交给 {@code error_msg}。
     *
     * <p>注意它<b>不含</b> robots 禁止（那是 {@link #STATUS_ROBOTS_DENIED}）：
     * 两者都是终态，但原因不同，运维据此判断的方向也不同。
     */
    public static final int STATUS_SKIPPED = 6;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归一化 URL 的 MD5（32 位十六进制）。同时是 {@code t_search_content.biz_id}。 */
    private String urlHash;

    /** 原始 URL（未归一化），用于回跳原文与人工排查。 */
    private String url;

    /** 域名，用于按站限速。与 urlHash 分开存是为了让限速查询走 {@code idx_domain} 而不必解析 URL。 */
    private String domain;

    /** 来源种子站。种子停用后，属于它的待抓 URL 不再被取出。 */
    private Long seedId;

    /**
     * 发现该 URL 的页面（urlHash）。
     *
     * <p>存下来是为了回答「这个页面是从哪爬来的」—— 内容出了问题需要溯源时，
     * 只靠 URL 本身无法还原采集路径。也是 {@link #depth} 之外防环的第二道保险。
     */
    private String parentUrlHash;

    /** 距种子入口的层级。 */
    private Integer depth;

    /** 状态机，取值见本类的 {@code STATUS_*} 常量。 */
    private Integer status;

    /** 最后一次抓取的 HTTP 状态码。为 null 表示还没抓过（连接失败也算没抓到）。 */
    private Integer httpStatus;

    /** 响应 ETag，下次请求带 {@code If-None-Match}。 */
    private String etag;

    /** 响应 Last-Modified，下次请求带 {@code If-Modified-Since}。 */
    private String lastModified;

    /** 正文 SHA-256（64 位十六进制）。未变则跳过写 ES 与重新送审，见类注释。 */
    private String contentHash;

    /** 已重试次数。达 {@code kuma.crawl.max-retry} 后转死信。 */
    private Integer retryCount;

    /** 下次可抓取时间。退避与按站限速都通过推迟这个值实现，而不是 sleep 占着线程。 */
    private LocalDateTime nextFetchTime;

    /** 最后一次抓取时间。 */
    private LocalDateTime lastFetchTime;

    /** 失败原因，截断到 1000 字符。仅用于排查，不参与任何判定。 */
    private String errorMsg;

    /**
     * 抓取租约开始时间，配合 {@link #status} = {@link #STATUS_FETCHING} 使用。
     *
     * <p>本期单机不使用（没有并发抢同一行的情况），但列先留着：
     * 多节点改造时靠它做超时回收，而加列需要停机改表，预留的成本远低于后补。
     */
    private LocalDateTime lockedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 该记录是否处于可抓取状态：待抓且已到可抓时间。
     *
     * <p>时间判定放在这里而不是 SQL 里，是因为「过没过期」在应用侧还需要用到
     * （例如日志与拒绝原因），两处用不同实现迟早会不一致。
     */
    public boolean isFetchable(LocalDateTime now) {
        if (status == null || status != STATUS_PENDING) {
            return false;
        }
        return nextFetchTime == null || !nextFetchTime.isAfter(now);
    }

    /** 正文是否与上一次抓取完全一致（用于跳过写 ES 与送审）。 */
    public boolean isContentUnchanged(String newHash) {
        return contentHash != null && contentHash.equals(newHash);
    }

    /** 是否为终态（不会再被调度）。 */
    public boolean isTerminal() {
        return status != null
                && (status == STATUS_DONE || status == STATUS_DEAD
                || status == STATUS_ROBOTS_DENIED || status == STATUS_SKIPPED);
    }
}
