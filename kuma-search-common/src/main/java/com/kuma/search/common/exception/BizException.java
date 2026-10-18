package com.kuma.search.common.exception;

import com.kuma.search.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。所有可预期的失败都应抛这个异常，由 {@code GlobalExceptionHandler} 统一转为 {@code R.fail(...)}。
 *
 * <p>刻意不继承 {@code RuntimeException} 之外的类型，也不吞掉 cause：
 * 依赖中间件（ES/Redis/DB）的异常应在各自 gateway / repository 层包装成本异常，
 * 并保留原始 cause，避免排查时丢失根因。
 */
@Getter
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMessage(), cause);
        this.resultCode = resultCode;
    }

    // ===== 便捷工厂 =====

    public static BizException of(ResultCode resultCode) {
        return new BizException(resultCode);
    }

    public static BizException of(ResultCode resultCode, String message) {
        return new BizException(resultCode, message);
    }

    /** 需要保留根因时使用 —— 包装中间件异常时务必带上 cause，否则排查时线索全断。 */
    public static BizException of(ResultCode resultCode, String message, Throwable cause) {
        return new BizException(resultCode, message, cause);
    }

    /** 参数不合法。 */
    public static BizException paramInvalid(String message) {
        return new BizException(ResultCode.PARAM_INVALID, message);
    }

    /** 资源不存在。 */
    public static BizException notFound(String message) {
        return new BizException(ResultCode.NOT_FOUND, message);
    }
}
