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
 * 采集内容的人工审核留痕，对应 {@code t_crawl_audit_log}。
 *
 * <p><b>只追加，不更新、不删除</b>（表上没有 {@code update_time}，也没有逻辑删除列）。
 * 这是刻意的：审核日志的价值全在「不可篡改」，一旦允许修改，
 * 「这条内容是谁放行的」这个问题就不再可信，而它恰恰是出了问题时唯一要回答的问题。
 *
 * <p>表里冗余存了 {@link #urlHash} 与 {@link #contentId} 两个指向同一页的标识，
 * 而不是只留 {@code content_id}：内容被物理清理后 {@code content_id} 会悬空，
 * 而 urlHash 是从 URL 推导出来的、永远可重算，保证日志在内容消失后依然能追溯。
 *
 * <p><b>审核动作不是只有「放行/驳回」两种。</b>{@link #ACTION_TAKEDOWN} 是内容已经进 ES
 * 之后才发生的撤回（投诉、发现违规）。它与 {@code REJECT} 的区别在于处理时机与后果：
 * {@code REJECT} 拦在入库之前，{@code TAKEDOWN} 需要额外触发一次 ES 删除。
 * 把两者混成一个状态会丢掉「这条是否曾经对外可见过」这个关键事实。
 *
 * @see com.kuma.search.crawl.CrawlAuditService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_crawl_audit_log")
public class CrawlAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审核通过，内容可以进 ES。 */
    public static final String ACTION_APPROVE = "APPROVE";
    /** 驳回，内容不进 ES（或从 ES 移除）。 */
    public static final String ACTION_REJECT = "REJECT";
    /**
     * 撤回：内容原本已发布，因投诉或事后发现违规而下架。
     *
     * <p>{@link #operator} 记为 {@code system} 时表示由规则自动触发（如重抓时命中敏感词）。
     */
    public static final String ACTION_TAKEDOWN = "TAKEDOWN";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归一化 URL 的 MD5，与 {@code t_crawl_url.url_hash} 同源。 */
    private String urlHash;

    /** 指向 {@code t_search_content.id}。内容被物理清理后可能悬空，此时靠 urlHash 追溯。 */
    private Long contentId;

    /** 动作，取值见本类的 {@code ACTION_*} 常量。 */
    private String action;

    /** 操作者用户名。自动规则产生的记录写 {@code system}。 */
    private String operator;

    /** 人工填写的理由。自动驳回时写触发规则，便于事后判断规则是否过严。 */
    private String reason;

    /**
     * 本次审核命中的敏感词，逗号分隔。
     *
     * <p>冗余存一份而不是每次去 {@code t_search_content.extra} 里翻：审核日志的用途之一
     * 是回溯「词库里的哪个词造成了这次驳回」，词库本身会被持续修改，
     * 事后再查只会得到「用今天的词库判昨天的事」这个错误答案。
     */
    private String matchedWords;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
