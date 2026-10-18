package com.kuma.search.constant;

import lombok.Getter;

/**
 * Outbox 操作类型：标记事件投递到 ES 时是写入还是删除。
 *
 * <p>与业务事件解耦——不管是新增还是修改，只要需要让文档最终存在都归为 UPSERT，
 * 消费者无需理解业务语义即可幂等执行。
 */
@Getter
public enum OutboxOpType {

    /** 写入或更新文档（新增与修改统一归为 UPSERT，保证幂等）。 */
    UPSERT("写入/更新"),

    /** 删除文档。 */
    DELETE("删除");

    /** 中文描述。 */
    private final String description;

    OutboxOpType(String description) {
        this.description = description;
    }
}
