package com.kuma.search.admin.exception;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常统一收敛成 {@code R} 响应体。
 *
 * <p>状态码约定：业务错误（含参数校验）用 HTTP 200 + 响应体里的 code 表达；
 * 只有认证 401、授权 403 使用对应状态码，便于前端拦截器按状态码触发登录/刷新流程。
 *
 * <p>安全底线：任何响应体都<b>不得</b>包含堆栈、SQL、ES 原始报错；
 * 系统异常的完整堆栈只进日志，响应体只给一个可追溯的 traceId。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 可预期的业务失败：用异常自带的 ResultCode 构造响应，HTTP 200。 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getResultCode().getCode(), e.getMessage());
        return R.fail(e.getResultCode(), e.getMessage());
    }

    /** {@code @RequestBody} 上的 {@code @Valid} 校验失败：message 必须点名具体字段。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> fieldName(err) + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败：{}", detail);
        return R.fail(ResultCode.PARAM_INVALID, detail.isBlank() ? ResultCode.PARAM_INVALID.getMessage() : detail);
    }

    /** 方法参数（如 {@code @RequestParam}）上的约束校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> propertyPath(v) + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败：{}", detail);
        return R.fail(ResultCode.PARAM_INVALID, detail.isBlank() ? ResultCode.PARAM_INVALID.getMessage() : detail);
    }

    /** 请求体本身无法解析（JSON 语法错误、类型不匹配）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());
        return R.fail(ResultCode.PARAM_INVALID, "请求体格式不正确，无法解析");
    }

    /** 已认证但无权限（方法级 {@code @PreAuthorize} 拒绝）：HTTP 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("拒绝访问：{}", e.getMessage());
        return R.fail(ResultCode.ACCESS_DENIED);
    }

    /** 认证失败：HTTP 401。 */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleAuthentication(AuthenticationException e) {
        log.warn("认证失败：{}", e.getMessage());
        return R.fail(ResultCode.UNAUTHORIZED);
    }

    /**
     * 兜底：未预期的异常。
     *
     * <p>日志打完整堆栈；响应体只给通用文案 + traceId，客服可凭 traceId 在日志中定位原始堆栈。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        log.error("系统异常 traceId={}", traceId, e);
        R<Void> result = R.fail(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.getMessage());
        result.setTraceId(traceId);
        return result;
    }

    private String fieldName(FieldError error) {
        return error.getField() == null || error.getField().isBlank() ? "参数" : error.getField();
    }

    private String propertyPath(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() == null ? "参数" : violation.getPropertyPath().toString();
    }
}
