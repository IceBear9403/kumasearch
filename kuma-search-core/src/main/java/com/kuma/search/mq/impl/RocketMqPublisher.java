package com.kuma.search.mq.impl;

import com.kuma.search.mq.IndexEvent;
import com.kuma.search.mq.IndexEventConsumer;
import com.kuma.search.mq.IndexEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RocketMQ 实现的<b>占位</b>（{@code kuma.mq.type=rocketmq}）。
 *
 * <h3>这里为什么是一个显式的失败实现，而不是一个空的 TODO 类</h3>
 * 如果只写一个空类或者干脆不提供这个实现，那么当有人把
 * {@code kuma.mq.type} 配成 {@code rocketmq} 时，Spring 会因为找不到
 * {@code IndexEventPublisher} 的 Bean 而在启动时抛出一个
 * {@code NoSuchBeanDefinitionException}。那个报错会把人引向"是不是包扫描写错了"，
 * 而真正的原因是这个功能压根没实现。
 *
 * <p>让它在启动时就明确地报出"RocketMQ 尚未接入，请改用 db-outbox"，
 * 把一次可能耗时半小时的排查变成一次 5 秒的阅读。
 *
 * <h3>接入时要做的事（写在这里，避免后人从零推断）</h3>
 * <ol>
 *   <li>在 POM 里加 {@code rocketmq-spring-boot-starter}；</li>
 *   <li>{@code publish} 改为发送到 {@code kuma.mq.topic}，消息 key 用 {@code eventId}
 *       （便于在控制台按 key 查询单条事件）；</li>
 *   <li>{@link #publishBatch} 用批量发送，注意单条消息体上限（默认 4MB），
 *       payload 里带完整文档快照，大正文需要评估是否会超限；</li>
 *   <li>消费者侧必须保留<b>幂等</b>：ES 的 {@code _id} 用业务主键这一点不能改，
 *       否则重投会产生重复文档；</li>
 *   <li><b>保留 Outbox 作为兜底</b>。切到 MQ 之后，"写 MySQL 成功但发 MQ 失败"这个窗口
 *       重新出现了，必须靠定期的全量对账（{@code ReconcileJob}）来发现 ——
 *       这也是为什么本项目的对账任务不是可选项。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kuma.mq.type", havingValue = "rocketmq")
public class RocketMqPublisher implements IndexEventPublisher, IndexEventConsumer {

    private static final String NOT_IMPLEMENTED =
            "RocketMQ 投递方式尚未接入。请将 kuma.mq.type 配置为 db-outbox（默认，推荐），"
                    + "或先完成 RocketMqPublisher 的实现。详见 docs/05-core-flows.md";

    public RocketMqPublisher() {
        // 在启动阶段就抛出，而不是等到第一次写入内容时才发现 ——
        // 那时的表现是"内容保存失败"，与本处的真实原因相去甚远。
        throw new IllegalStateException(NOT_IMPLEMENTED);
    }

    @Override
    public void publish(IndexEvent event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void publishBatch(List<IndexEvent> events) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public List<IndexEvent> poll(int batchSize) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    /**
     * 积压量。真正的实现应读取 RocketMQ 的消费堆积指标
     * （{@code admin.fetchConsumeStats(topic)} 的 {@code diffTotal}）。
     *
     * <p>这里返回 -1 而不是 0：本类在构造阶段就已经抛异常，正常永远不会被实例化；
     * 万一将来有人注释掉那行构造器让它跑起来，-1 会让"积压统计不可用"直接暴露在监控上，
     * 而返回 0 会伪装成"没有积压"，是最危险的默认值。
     */
    @Override
    public long backlogSize() {
        return -1L;
    }

    @Override
    public void ack(IndexEvent event) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void nack(IndexEvent event, String error) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
