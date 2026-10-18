package com.kuma.search.service;

import com.kuma.search.repository.entity.SearchContent;

import java.util.List;

/**
 * 内容管理服务 —— 写入链路的起点。
 *
 * <h3>这个接口最关键的约定</h3>
 * 所有写方法都<b>必须</b>在同一个本地事务里完成两件事：
 * 写 {@code t_search_content}，以及通过 {@code IndexEventPublisher} 写一条 Outbox 事件。
 *
 * <p>为什么必须同事务：如果先写业务表再发事件（或先发事件再写表），
 * 中间任何一步失败都会造成「业务数据在、搜索搜不到」或「搜索有、详情页 404」。
 * 前者用户会反复重试并质疑系统，后者更糟——点进去是空的。
 * 同事务写入后，事件与数据要么都成功要么都失败，把一致性问题收敛成「事件什么时候被消费」，
 * 而不是「事件会不会丢」。
 *
 * <p>ES 的写入本身是<b>异步</b>的（由 Outbox 消费者完成），因此本接口的返回
 * 只代表「MySQL 已持久化」，不代表「已可搜索」。可搜索的延迟由刷新间隔与消费速度决定，
 * 通常在一秒内。
 */
public interface ContentService {

    /**
     * 新增或更新内容。
     *
     * <p>幂等语义：以 {@code (typeCode, bizId)} 为唯一键，已存在则覆盖。
     *
     * @return 记录主键
     */
    Long save(SearchContent content);

    /** 按类型与业务主键删除。 */
    void delete(String typeCode, String bizId);

    /** 批量保存。用于批量导入场景，内部应按批提交事务而不是逐条。 */
    int batchSave(List<SearchContent> contents);

    /** 按类型与业务主键查询。 */
    SearchContent get(String typeCode, String bizId);

    /**
     * 该类型下未删除的内容条数。
     *
     * <p>只被一处用到：删除类型配置前的前置检查（还有内容的类型不能删配置，
     * 否则那些内容会变成既搜不到也删不掉的孤儿）。不提供「按状态筛」等更细的口径 ——
     * 需要那种统计的地方应该直接查 {@code t_search_content}。
     */
    long countAliveByType(String typeCode);
}
