package com.kuma.search.mq;

import com.kuma.search.constant.OutboxOpType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条索引变更事件 —— 写入链路的唯一载体。
 *
 * <p>无论底层用数据库 Outbox 表、内存队列还是将来换成 RocketMQ/Kafka，
 * 流转的都是这个对象。这是「业务代码不感知 MQ 实现」这条约束的具体形态。
 *
 * <h3>幂等从哪来（以及 eventId 不负责这件事）</h3>
 * 幂等由 <b>ES 侧</b>提供：文档的 {@code _id} 用业务主键 {@link #bizId}，
 * 同一篇内容被重复投递多少次，结果都是写向同一个位置，重复的那次只是覆盖。
 *
 * <p>因此<b>没有「按 eventId 去重」这回事</b>，也不需要 ——
 * 同一文档先后产生两条内容不同的事件时，两条都<em>必须</em>被应用，
 * 按 eventId 去重反而会丢掉后一次修改。
 *
 * <p>{@link #eventId} 的实际用途是给消费端的 {@code ack}/{@code nack}
 * 定位要更新哪一行 Outbox 记录。它仍应由生产端生成：
 * 让中间件生成的话，重投时无法回指到原始那一行，ack 就落空了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexEvent implements Serializable {

    /**
     * 事件唯一标识，由生产端生成（UUID）。
     * 用于让 {@code ack}/{@code nack} 定位 Outbox 行，<b>不</b>用于去重 —— 见类注释。
     */
    private String eventId;

    /** 索引类型编码。 */
    private String typeCode;

    /** 业务主键，直接用作 ES 的 {@code _id}，保证 upsert 幂等。 */
    private String bizId;

    /** 操作类型：UPSERT（新增或全量覆盖）或 DELETE。 */
    private OutboxOpType opType;

    /**
     * 文档内容快照（JSON 字符串）。
     *
     * <p>刻意<b>携带完整快照</b>而不是只带 bizId 让消费端回查数据库：
     * 回查会引入「消费时数据已被再次修改」的竞态 —— 消费端读到的是新值，
     * 却按旧事件的顺序写入，最终 ES 里的版本可能不是最新的。
     * 带快照后，事件之间的先后顺序直接决定最终结果，语义清晰。
     * 代价是事件表会大一些，可通过消费成功后及时清理来缓解。
     */
    private String payload;

    /** 事件产生时间，用于对账与排查延迟。 */
    private LocalDateTime occurredAt;
}
