package com.kuma.search.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kuma.search.repository.entity.CrawlUrl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采集 URL 队列 Mapper。
 *
 * <p>这里刻意保留了几条注解 SQL，而不是全部走 {@code BaseMapper} 的包装方法。
 * 原因是队列的两个核心操作都必须是<b>单条原子语句</b>，
 * 拆成「先查后改」会在多线程/多实例下产生重复抓取：
 *
 * <ul>
 *   <li>{@link #claimBatch} —— 认领一批待抓 URL，条件是「仍处于待抓状态」，
 *       命中 0 行就说明被别人抢走了。把状态判定写进 {@code WHERE} 而不是先 SELECT 再 UPDATE，
 *       是为了让数据库的行锁来保证互斥。</li>
 *   <li>{@link #recoverExpiredLeases} —— 回收崩溃进程留下的「抓取中」记录。
 *       只回收超过租约时长的，正在正常抓取的行不会被误伤。</li>
 * </ul>
 *
 * <p>取任务走 {@code idx_status_next (status, next_fetch_time, id)}，
 * {@code ORDER BY id} 是让它能按索引顺序扫描后提前停止，而不是全表排序。
 */
@Mapper
public interface CrawlUrlMapper extends BaseMapper<CrawlUrl> {

    /**
     * 认领一批待抓 URL。
     *
     * <p>执行顺序是「先选出 id 列表 → 再按 id 批量改状态」，两步都在同一条语句里完成时
     * MySQL 不允许对同一张表边 SELECT 边 UPDATE，因此拆成两次调用，由
     * {@code DbCrawlFrontier} 保证顺序，并用「更新影响行数」判断是否真的抢到。
     *
     * <p><b>{@code status IN (0, 3)} 里的 3 不能少</b>：失败待重试（{@link CrawlUrl#STATUS_RETRY}）
     * 是 {@code markRetry} 写入的状态，它的语义就是「按 {@code next_fetch_time} 退避后再试」。
     * 只认 {@code status = 0} 的后果是**任何失败过一次的 URL 都再也不会被取到**：
     * 退避秒数、{@code retry_count}、以及「超过上限转死信」那条分支全都成了不可达代码，
     * 而库里只留下一批永远停在「失败待重试」的行 —— 界面上那个计数还会一直涨。
     * 真正的「不重试」由终态表达（{@code isTerminalStatus}），不是靠这里取不到。
     *
     * @param now   当前时间，用于判定 {@code next_fetch_time} 是否已到（退避就靠它生效）
     * @param limit 最多取多少条
     */
    @Select("""
            SELECT * FROM t_crawl_url
             WHERE status IN (0, 3)
               AND (next_fetch_time IS NULL OR next_fetch_time <= #{now})
             ORDER BY id
             LIMIT #{limit}
            """)
    List<CrawlUrl> selectFetchable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 把一批 URL 置为「抓取中」并打上租约。
     *
     * <p>{@code AND status IN (0, 3)} 是这条件语句的关键：只有仍未被别人动过的行才会被更新
     * （待抓，或失败待重试），返回的影响行数就是本次真正抢到的数量。少了这个条件，
     * 两个调度器会把同一批 URL 各抓一遍。<b>这里的取值必须与 {@link #selectFetchable} 一致</b> ——
     * 少了 3，重试的行会在「选出来」与「改状态」之间空手而归（影响行数 0 等于没抢到），
     * 表现为重试永远不生效，且没有任何报错。
     *
     * @return 实际更新的行数，通常等于 {@code ids.size()}；小于则表示有行被抢先处理了
     */
    @Update("""
            <script>
            UPDATE t_crawl_url
               SET status = 1,
                   locked_at = #{now},
                   last_fetch_time = #{now},
                   update_time = #{now}
             WHERE status IN (0, 3)
               AND id IN
               <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int claimBatch(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * 回收超时的抓取租约：把「抓取中」且租约已过期的记录退回待抓。
     *
     * <p>不递增 {@code retry_count}。进程崩溃是运维事件，不是这个 URL 的问题，
     * 让它消耗重试次数会导致「服务重启三次后一批正常 URL 集体进死信」。
     *
     * @return 回收的行数。持续大于 0 说明有实例在反复崩溃，应当告警
     */
    @Update("""
            UPDATE t_crawl_url
               SET status = 0,
                   locked_at = NULL,
                   update_time = #{now}
             WHERE status = 1
               AND locked_at IS NOT NULL
               AND locked_at < #{expireBefore}
            """)
    int recoverExpiredLeases(@Param("expireBefore") LocalDateTime expireBefore,
                             @Param("now") LocalDateTime now);

    /**
     * 统计某个种子下已成功落库的页数，用于 {@code max_pages} 配额判定。
     *
     * <p>只数 {@code status=2}：失败与跳过的不占配额，否则一批 404 就能把种子配额耗光。
     */
    @Select("SELECT COUNT(*) FROM t_crawl_url WHERE seed_id = #{seedId} AND status = 2")
    int countFetchedBySeed(@Param("seedId") Long seedId);

    /**
     * 统计某个域名的队列积压量，供后台展示与限速参考。
     */
    @Select("SELECT COUNT(*) FROM t_crawl_url WHERE domain = #{domain} AND status = 0")
    int countPendingByDomain(@Param("domain") String domain);
}
