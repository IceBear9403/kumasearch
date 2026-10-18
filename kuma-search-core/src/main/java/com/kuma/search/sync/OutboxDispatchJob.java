package com.kuma.search.sync;

import com.kuma.search.cache.CacheKeys;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Outbox 投递任务：把事件表里待处理的事件推给 ES。
 *
 * <p>这是「写入 MySQL」到「可被搜索」之间唯一的搬运工。它的滞后程度直接决定了
 * 用户看到「刚发布的内容搜不到」的时长，因此扫描频率设在秒级。
 *
 * <h3>本类的核心职责其实不是"搬运"，而是"别把自己压垮"</h3>
 * 三个必须守住的边界：
 * <ol>
 *   <li><b>单次执行的事件数有上限</b>。积压十万条事件时，一次全捞进来会把
 *       内存和 ES 一起打爆。分批处理、每批之后重新调度，让积压被"细水长流"地消化。</li>
 *   <li><b>一条失败不中断整批</b>。失败的事件走 nack 进入退避队列，
 *       后面的正常事件继续处理 —— 否则一条坏数据会阻塞整个索引更新。</li>
 *   <li><b>ES 整体不可用时快速退出</b>。连续失败说明是集群级故障而非单条数据的毛病，
 *       这时继续逐条重试只是在放大故障，应当立刻停止，等下一轮再试。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatchJob {

    private final IndexEventConsumer indexEventConsumer;
    private final IndexEventHandler indexEventHandler;
    private final KumaSearchProperties properties;
    private final DistributedLock distributedLock;

    /**
     * 单次执行最多处理的事件数。
     *
     * <p>取值的依据：每次处理包含一次 ES 批量写，单次往返约 10ms。
     * 2000 条按 500 一批是 4 次往返，控制在 1 秒内完成，
     * 不会让 {@code fixedDelay} 的调度周期被无限推迟。
     */
    private static final int MAX_EVENTS_PER_RUN = 2000;

    /** 连续失败多少次后判定为"ES 集群级故障"并中止本轮。 */
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 10;

    /**
     * 扫描并投递。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是"上一次执行结束后再等 N 毫秒"，
     * 后者是"每 N 毫秒触发一次，不管上一次有没有跑完"。在 ES 变慢时，
     * {@code fixedRate} 会让任务堆积成串行的积压队列，而 {@code fixedDelay} 自然降频。
     */
    @Scheduled(fixedDelayString = "${kuma.mq.outbox-scan-interval-millis:1000}")
    public void dispatch() {
        String lockKey = CacheKeys.syncTaskLock("outbox-dispatch");
        // TTL 给 2 分钟：正常情况下单轮只有几百毫秒，2 分钟已经是极度异常的信号，
        // 此时让锁过期、让另一个实例接手，比死等一个卡住的实例更好。
        String token = distributedLock.tryLock(lockKey, Duration.ofMinutes(2));
        if (token == null) {
            return;
        }

        try {
            int processed = 0;
            int failed = 0;
            int consecutiveFailures = 0;

            while (processed + failed < MAX_EVENTS_PER_RUN) {
                int batchSize = properties.getSync().getBatchSize();
                List<IndexEvent> events = indexEventConsumer.poll(batchSize);
                if (events.isEmpty()) {
                    break;
                }

                for (IndexEvent event : events) {
                    try {
                        indexEventHandler.handle(event);
                        indexEventConsumer.ack(event);
                        processed++;
                        consecutiveFailures = 0;
                    } catch (Exception e) {
                        indexEventConsumer.nack(event, failureReason(e));
                        failed++;
                        consecutiveFailures++;

                        // 连续失败这么多次，说明不是某一条数据的问题，而是 ES 或网络整体不可用。
                        // 继续循环只会把另外几千条事件全部推入退避队列（且退避次数+1），
                        // 让它们在 ES 恢复后还要多等一轮退避 —— 这是帮倒忙。
                        if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
                            log.error("连续 {} 条事件投递失败，判定为 ES 集群级故障，中止本轮投递。"
                                            + "注意这条判断只看「连续多少条」，单条数据的永久性错误"
                                            + "（例如字段类型与 mapping 对不上）同样会走到这里 —— "
                                            + "下一条的原因是同一条数据的同一句话时，那就不是集群故障",
                                    consecutiveFailures, e);
                            return;
                        }
                    }
                }

                // 本批不足一整批，说明已经追平，不必再查
                if (events.size() < batchSize) {
                    break;
                }
            }

            if (processed > 0 || failed > 0) {
                log.info("Outbox 投递完成，成功={}，失败={}", processed, failed);
            }
        } catch (Exception e) {
            // 最外层兜底：任何未预期的异常都不能让调度线程死掉，
            // 否则 @Scheduled 会静默停止工作，索引从此不再更新且没有任何报错。
            log.error("Outbox 投递任务异常终止", e);
        } finally {
            distributedLock.unlock(lockKey, token);
        }
    }

    /**
     * 手工触发一次投递，供管理后台的「立即同步」按钮与故障恢复后使用。
     *
     * <p>不需要加锁：{@link #dispatch} 本身有锁，重复触发的最坏结果是空跑一轮。
     */
    public void dispatchNow() {
        dispatch();
    }

    /**
     * 把异常描述成一行「最外层消息 ← 根因」，供 nack 落进 {@code t_search_outbox.error_msg}。
     *
     * <h3>为什么不能只写 {@code e.getMessage()}</h3>
     * 写入链路的异常是被<b>逐层包装</b>的：{@code EsBulkGateway} 把 ES 客户端异常包成
     * {@code BizException("写入文档失败，index=…, id=…")}，真正的原因（比如
     * {@code mapper_parsing_exception: failed to parse field [publishTime]}）在 cause 链的末端。
     * 只取最外层，落到库里的就是一句没有任何信息量的话：
     *
     * <pre>
     *   写入文档失败，index=kuma_article_v1, id=notice-how-to-search
     * </pre>
     *
     * 而 {@code error_msg} 恰恰是排查时唯一能拿到的东西 —— 事故当天那条日志指向的
     * 是"ES 写不进去"，而实际情况是"这一条文档里的日期格式与 mapping 对不上"，
     * 两者的排查方向完全不同（前者会去查 ES 集群，后者要去查索引的 mapping）。
     *
     * <h3>为什么要分段截断，而不是拼完再交给 {@code truncateError}</h3>
     * {@code t_search_outbox.error_msg} 是 VARCHAR(1000)，超长部分会被从<b>尾部</b>截掉。
     * 根因在尾部 —— 先拼再截，恰好会把唯一有用的那半句丢掉。
     * 所以这里先把头尾各截到限额（头 200、尾 700，加前缀共约 920），
     * 保证无论根因多长，它都还在。
     */
    private static String failureReason(Throwable e) {
        String head = brief(e.getMessage(), e.getClass().getSimpleName(), 200);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String tail = brief(root.getMessage(), root.getClass().getSimpleName(), 700);
        if (tail.isEmpty() || head.contains(tail)) {
            return head;
        }
        return head + " ← 根因: " + tail;
    }

    /** 取消息（为空时退化成异常类名），并截断到 max 个字符。 */
    private static String brief(String message, String fallback, int max) {
        String text = (message == null || message.isBlank()) ? fallback : message.trim();
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 供健康检查使用：返回当前积压的事件数。 */
    public long backlogSize() {
        // 交给消费端自己统计（COUNT 或队列长度），不要在这里 poll 一批出来数 size()：
        // 那样每调一次就要把上千条含整篇正文的 payload 反序列化出来，只为得到一个数字，
        // 而健康检查是会被反复调用的。返回 -1 表示统计失败，调用方不应把它当成 0。
        return indexEventConsumer.backlogSize();
    }
}
