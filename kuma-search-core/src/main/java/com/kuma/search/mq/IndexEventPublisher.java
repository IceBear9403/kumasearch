package com.kuma.search.mq;

import java.util.List;

/**
 * 索引事件发布接口 —— 写入链路的<b>唯一出口</b>。
 *
 * <p>业务代码（内容管理、批量导入、索引重建）只依赖这个接口，
 * 因此从「数据库 Outbox」演进到「RocketMQ」时，只需要新增一个实现类并切换 Bean，
 * <b>业务代码一行都不用改</b>。这是整个项目里返工收益最高的一个抽象点。
 *
 * <h3>与事务的关系</h3>
 * 实现方需要保证：业务写入（MySQL 的 {@code t_search_content}）与事件写入
 * 在<b>同一个本地事务</b>里提交。这样不存在「业务写成功但事件丢了」的窗口。
 * 跨进程的 MQ 实现拿不到这个保证，必须靠「本地消息表 + 对账补偿」来兜底 ——
 * 这也是为什么当前选择 Outbox 而不是直接上 MQ。
 */
public interface IndexEventPublisher {

    /**
     * 发布单条索引事件。
     *
     * <p>调用方应当已经处于事务上下文中。实现方不得在此方法内做远程调用，
     * 否则事务会被网络等待拖长，放大锁竞争。
     */
    void publish(IndexEvent event);

    /**
     * 批量发布。批量导入场景下逐条插入事件表会产生大量小事务，
     * 批量接口能让实现方合并为一次插入。
     */
    void publishBatch(List<IndexEvent> events);
}
