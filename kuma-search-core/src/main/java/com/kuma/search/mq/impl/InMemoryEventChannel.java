package com.kuma.search.mq.impl;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventConsumer;
import com.kuma.search.mq.IndexEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内事件通道，仅在本地开发与单元测试中使用（{@code kuma.mq.type=memory}）。
 *
 * <h3>它存在的唯一价值：让开发者不用为了跑一次搜索而装 MySQL 服务</h3>
 * 拉起一个本地的 MySQL 只为了验证一次检索逻辑，是很重的负担。
 * 这个实现把事件留在内存里，配合 {@code db-outbox} 之外的轻量运行方式，
 * 可以让"改代码 → 看到效果"的循环缩短到几秒。
 *
 * <h3>它绝不可以在生产环境使用</h3>
 * 三个致命缺陷，缺一不可地决定了这一点：
 * <ol>
 *   <li><b>进程重启即丢事件</b>。而恰恰是重启前那一刻的数据最需要被索引；</li>
 *   <li><b>它破坏了"与业务写入同事务"这个唯一的强保证</b>。业务事务回滚了，
 *       事件却已经在内存队列里，消费者会去 ES 写一条数据库中不存在的文档；</li>
 *   <li><b>多实例时各写各的队列</b>，实例 A 写入的数据由 B 处理的概率为零，
 *       索引更新变得不可预测。</li>
 * </ol>
 *
 * <p>队列<b>有界</b>而不是无界：无界队列在消费者跟不上时会把堆吃光，
 * 表现为 OOM 而不是一个明确的错误。有界队列在满了之后会阻塞生产线程 ——
 * 那正是我们想要的背压（与 {@code AsyncConfig} 里 indexEventExecutor 用
 * CallerRunsPolicy 是同一个思路：宁可让业务变慢，也不要丢索引事件）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kuma.mq.type", havingValue = "memory")
public class InMemoryEventChannel implements IndexEventPublisher, IndexEventConsumer {

    /**
     * 队列容量。取值参考：单条事件约 1KB，1 万条约 10MB ——
     * 这个量级的堆占用对一个开发环境来说是安全的。
     */
    private static final int QUEUE_CAPACITY = 10000;

    private final BlockingQueue<IndexEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * 已被 poll 取出但尚未 ack/nack 的事件。
     *
     * <p>必须单独存一份，否则 poll 之后事件就离开了队列，
     * ack 无从得知它对应哪条；而 nack 要能把它重新放回去重试。
     */
    private final Map<String, IndexEvent> inFlight = new ConcurrentHashMap<>();

    /** 重试次数，用于限制死循环。仅开发环境使用，不需要持久化。 */
    private final Map<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    private static final int MAX_RETRY = 5;

    @Override
    public void publish(IndexEvent event) {
        if (event == null) {
            return;
        }
        try {
            // offer + 阻塞等待：队列满时阻塞生产线程，形成背压而不是丢弃事件。
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ResultCode.SYSTEM_ERROR, "写入内存事件队列被中断", e);
        }
    }

    @Override
    public void publishBatch(List<IndexEvent> events) {
        if (events == null) {
            return;
        }
        events.forEach(this::publish);
    }

    @Override
    public List<IndexEvent> poll(int batchSize) {
        List<IndexEvent> batch = new ArrayList<>(Math.min(batchSize, 256));
        queue.drainTo(batch, batchSize);
        batch.forEach(e -> inFlight.put(e.getEventId(), e));
        return batch;
    }

    /** 队列中尚未被拉取的条数，O(1) 且无对象拷贝。 */
    @Override
    public long backlogSize() {
        return queue.size();
    }

    @Override
    public void ack(IndexEvent event) {
        if (event == null) {
            return;
        }
        inFlight.remove(event.getEventId());
        retryCounts.remove(event.getEventId());
    }

    @Override
    public void nack(IndexEvent event, String error) {
        if (event == null) {
            return;
        }
        inFlight.remove(event.getEventId());

        int retried = retryCounts
                .computeIfAbsent(event.getEventId(), k -> new AtomicInteger())
                .incrementAndGet();

        if (retried > MAX_RETRY) {
            // 开发环境下把死信直接打到 ERROR 日志，比提供一个查询接口更容易被发现
            log.error("[memory 模式] 事件重试 {} 次仍失败，已丢弃，eventId={}，bizId={}，原因={}",
                    retried, event.getEventId(), event.getBizId(), error);
            retryCounts.remove(event.getEventId());
            return;
        }
        log.warn("[memory 模式] 事件处理失败，第 {} 次重试，eventId={}，原因={}",
                retried, event.getEventId(), error);
        queue.offer(event);
    }

    /** 供开发期观察积压情况。 */
    public int size() {
        return queue.size();
    }
}
