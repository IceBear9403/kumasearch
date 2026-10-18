package com.kuma.search.constant;

import lombok.Getter;

/**
 * Outbox 事件处理状态：驱动投递线程的扫描条件与重试退避。
 *
 * <p>用 code 落库而非枚举名，是为了让状态值稳定可索引且便于 SQL 过滤；
 * 只有 PENDING 与 FAILED 会被重试扫描拾取，DEAD 保留现场等待人工干预而不自动重投。
 */
@Getter
public enum OutboxStatus {

    /** 待处理：尚未投递或退避时间已到，可被扫描。 */
    PENDING(0, "待处理"),

    /** 已成功：投递到 ES 成功，终态。 */
    SUCCESS(1, "成功"),

    /** 失败待重试：本次投递失败，等待下次退避重试。 */
    FAILED(2, "失败待重试"),

    /** 死信：重试超过阈值，停止自动重投，等待人工排查。 */
    DEAD(3, "死信");

    /** 落库用的状态码。 */
    private final int code;

    /** 中文描述。 */
    private final String description;

    OutboxStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
