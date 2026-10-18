package com.kuma.search.mq;

import java.util.List;

/**
 * 索引事件消费接口。
 *
 * <p>与 {@link IndexEventPublisher} 分开定义而不是合成一个，是因为生产端与消费端
 * <b>部署形态不同</b>：生产端在 Web 请求线程里，消费端在后台线程/独立进程中。
 * 合在一起会让人误以为可以随意互调，也妨碍将来把消费端拆成单独的部署单元。
 *
 * <h3>ack / nack 语义</h3>
 * 采用「至少一次」投递：{@link #ack} 表示处理成功、可以安全删除或标记；
 * {@link #nack} 表示处理失败，实现方应按退避策略重投，超过上限后转入死信。
 *
 * <h3>重复投递靠什么消化</h3>
 * <b>幂等由 ES 侧提供，而不是由本接口的消费端提供。</b>
 * 文档的 {@code _id} 用业务主键（{@code bizId}）而非随机值，因此同一个事件被投递两次，
 * 结果都是把同一篇文档写向同一个位置 —— 第二次是纯粹的覆盖，没有副作用。
 * 也就是说：<b>不需要、也没有实现「按 eventId 去重」</b>。
 * {@link IndexEvent#getEventId()} 的实际用途是给 {@link #ack}/{@link #nack}
 * 定位要更新哪一行，而不是去重表。
 *
 * <p>有一条容易被忽略的边界：幂等只在「事件内容相同」时成立。
 * 若同一文档先后产生了两条内容不同的事件，它们<em>都必须</em>被应用，
 * 所以去重反而会是错的。这也是采用 {@code _id} 覆盖而非事件去重的原因之一。
 */
public interface IndexEventConsumer {

    /**
     * 拉取一批待处理事件。
     *
     * @param batchSize 单批上限。过大会让单次失败的影响面变大，过小则吞吐不足。
     * @return 待处理事件，无数据时返回空列表（而非 null）
     */
    List<IndexEvent> poll(int batchSize);

    /**
     * 当前积压的待处理事件数，用于监控与告警。
     *
     * <p>实现方<b>不要</b>用 {@code poll(n).size()} 来实现它：那会把 n 条事件的完整
     * payload（含整篇正文）反序列化出来，只为得到一个数字。这个方法是给健康检查与
     * 监控频繁调用的，必须走 COUNT 或队列长度这类不产生对象拷贝的路径。
     *
     * <p>返回 -1 表示当前实现拿不到这个数字，调用方不应把它当成 0。
     */
    long backlogSize();

    /** 处理成功。 */
    void ack(IndexEvent event);

    /**
     * 处理失败。
     *
     * @param error 失败原因，会被截断后写入事件表，供运维排查
     */
    void nack(IndexEvent event, String error);
}
