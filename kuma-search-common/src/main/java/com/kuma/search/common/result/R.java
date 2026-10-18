package com.kuma.search.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 统一响应体。所有 REST 接口的返回值都包一层，前端只需判断 {@code code == 0}。
 *
 * <p>示例：
 * <pre>{@code
 * { "code": 0, "message": "成功", "data": { ... }, "timestamp": 1730000000000 }
 * }</pre>
 *
 * <p>约定：{@code code == 0} 表示业务成功；非 0 时 {@code data} 恒为 {@code null}，
 * 由 {@code GlobalExceptionHandler} 统一填充。
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    /** 服务端毫秒时间戳，便于前端排查时序问题与做耗时对齐。 */
    private long timestamp = Instant.now().toEpochMilli();
    /** 链路追踪 ID，与日志中的 traceId 一致，便于问题定位。 */
    private String traceId;

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ===== 成功 =====

    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    // ===== 失败 =====

    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> R<T> fail(ResultCode resultCode, String message) {
        return new R<>(resultCode.getCode(), message, null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
