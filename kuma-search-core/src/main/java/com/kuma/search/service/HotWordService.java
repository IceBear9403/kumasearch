package com.kuma.search.service;

import java.util.List;

/**
 * 热词榜与个人搜索历史。
 *
 * <h3>为什么热词不走 MySQL</h3>
 * 「每搜一次就写一条 DB 记录」在 500 QPS 下就是每秒 500 次写入，
 * 而搜索日志的价值（统计、离线分析）并不要求实时落库。
 * 因此热词用 Redis ZSET 做 {@code ZINCRBY}，天然完成「计数 + 排序」两件事；
 * MySQL 的 {@code t_search_log} 只作为异步落库的持久化与离线分析数据源。
 */
public interface HotWordService {

    /**
     * 记录一次搜索（不带来源 IP）。
     *
     * <p>必须<b>异步且可失败</b>：记录热词失败绝不能让用户看到错误。
     * 实现方应把异常吞掉并记日志，而不是向上抛。
     *
     * @param typeCode 类型编码
     * @param keyword  原始关键词
     * @param userId   用户 ID，为 null 时不影响热词统计，但不会写个人历史
     */
    void record(String typeCode, String keyword, Long userId);

    /**
     * 记录一次搜索，并按来源 IP 做热词去重。
     *
     * <p><b>为什么需要 {@code clientIp}：</b>搜索匿名可用之后，任何人都能反复搜同一个词
     * 把它刷上热词榜。去重的身份只能来自 IP —— 未登录用户没有 userId，
     * 这正是最需要防的场景。用 userId 做 key 的话，防的全是已经登录的良性用户。
     *
     * <p>去重的语义是「同一 IP + 同一关键词在窗口内只计一次热词」，
     * <b>不影响个人历史</b>：历史是用户自己的记录，去重它会让「我刚搜过的词不在历史里」。
     *
     * <p>⚠️ 只抬高成本，挡不住分布式刷量。热词榜仍需人工巡检，
     * 详见 {@code CacheKeys#hotDedup}。
     *
     * @param clientIp 客户端 IP；为 null 或空时不做去重（无法确定身份时宁可多计，
     *                 也不要因为缺少身份就把所有人的搜索都记成热词或都不记）
     */
    void record(String typeCode, String keyword, Long userId, String clientIp);

    /**
     * 获取热词榜。
     *
     * @param typeCode 类型编码
     * @param size     返回条数
     */
    List<String> topHotWords(String typeCode, int size);

    /** 获取某个用户的搜索历史，按时间倒序。 */
    List<String> userHistory(Long userId, String typeCode, int size);

    /**
     * 批量导入搜索历史（未登录期间的本地记录）。
     *
     * <p><b>只写历史，绝不触碰热词榜。</b>这是本方法与 {@link #record} 的关键区别：
     * 本地历史是匿名期间攒下来的一批词，把它们一次性灌进热词 ZSET
     * 等于让一次「导入」操作凭空造出一批热词 —— 一个用户导入 50 个词，
     * 每个词的计数都会 +1，而它们从未被"搜索"过。热词榜反映的是当下的检索热度，
     * 不是某个用户过去的检索总量。
     *
     * <p>重复导入是幂等的：写入的是 ZSET，同一关键词只会覆盖 score。
     *
     * @param userId   必须非空。调用方（{@code /api/auth/history/import}）已强制登录
     * @param typeCode 类型编码，历史在 Redis 里按类型分 key
     * @param keywords 关键词列表，<b>按时间倒序</b>（最近的在前）——
     *                 实现据此分配递减的 score，从而在导入后保持原有顺序
     */
    void importHistory(Long userId, String typeCode, List<String> keywords);

    /** 清空某个用户在某个类型下的搜索历史。 */
    void clearHistory(Long userId, String typeCode);

    /**
     * 归一化关键词。
     *
     * <p>「华为手机」「华为 手机」「 华为手机 」应当被统计为同一个词，
     * 否则热词榜会被同一批词的书写差异冲散。归一化规则：去首尾空白、
     * 内部连续空白压成一个空格、转小写。
     */
    String normalize(String keyword);
}
