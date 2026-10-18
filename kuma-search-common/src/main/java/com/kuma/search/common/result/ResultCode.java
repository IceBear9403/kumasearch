package com.kuma.search.common.result;

import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p>编码规范：5 位数字 {@code ABCCC}
 * <ul>
 *   <li>A —— 错误来源：1 通用 / 2 认证授权 / 3 参数校验 / 4 业务 / 5 依赖中间件</li>
 *   <li>B —— 大类：0 通用 / 1 搜索 / 2 索引 / 3 同步 / 4 用户</li>
 *   <li>CCC —— 序号</li>
 * </ul>
 *
 * <p>完整错误码表见 {@code docs/04-api-design.md}。
 */
@Getter
public enum ResultCode {

    // ===== 1xxxx 通用 =====
    SUCCESS(0, "成功"),
    SYSTEM_ERROR(10001, "系统内部错误"),
    REQUEST_REJECTED(10002, "请求被拒绝"),
    RATE_LIMITED(10003, "请求过于频繁，请稍后重试"),
    NOT_FOUND(10004, "资源不存在"),
    METHOD_NOT_ALLOWED(10005, "请求方法不支持"),

    // ===== 2xxxx 认证与授权 =====
    UNAUTHORIZED(20001, "未登录或登录已过期"),
    TOKEN_INVALID(20002, "令牌无效"),
    TOKEN_EXPIRED(20003, "令牌已过期"),
    BAD_CREDENTIALS(20004, "用户名或密码错误"),
    ACCOUNT_DISABLED(20005, "账号已被禁用"),
    ACCESS_DENIED(20006, "无权访问该资源"),
    CAPTCHA_INVALID(20007, "图形验证码校验未通过"),
    EMAIL_CODE_INVALID(20008, "邮箱验证码错误或已失效"),
    EMAIL_SEND_TOO_FREQUENT(20009, "验证码发送过于频繁，请稍后再试"),
    EMAIL_QUOTA_EXCEEDED(20010, "今日邮件发送额度已用尽，请明日再试"),
    USERNAME_EXISTS(20401, "用户名已存在"),
    EMAIL_EXISTS(20402, "邮箱已被注册"),

    // ===== 3xxxx 参数校验 =====
    PARAM_INVALID(30001, "请求参数不合法"),
    PAGE_SIZE_EXCEEDED(30002, "分页大小超出上限"),
    PASSWORD_TOO_WEAK(30003, "密码强度不足"),

    // ===== 4xxxx 业务 =====
    SEARCH_TYPE_NOT_FOUND(41001, "搜索类型未配置或已停用"),
    SEARCH_FIELD_NOT_ALLOWED(41002, "请求的字段不允许用于过滤/排序/聚合"),
    SEARCH_QUERY_TOO_COMPLEX(41003, "查询条件过于复杂"),
    INDEX_TYPE_CODE_EXISTS(42001, "类型编码已存在"),
    INDEX_NOT_READY(42002, "索引尚未就绪"),
    INDEX_ALIAS_SWITCH_FAILED(42003, "索引别名切换失败"),
    CONTENT_NOT_FOUND(43001, "内容不存在"),
    CONTENT_DUPLICATED(43002, "同一类型下业务主键重复"),

    // ===== 5xxxx 依赖中间件 =====
    ES_UNAVAILABLE(50001, "搜索引擎暂时不可用"),
    ES_QUERY_FAILED(50002, "搜索执行失败"),
    DB_ERROR(50003, "数据库操作失败"),
    REDIS_ERROR(50004, "缓存服务异常");

    /** 业务错误码，0 表示成功。 */
    private final int code;
    /** 面向用户/前端的提示信息，不含堆栈等敏感内容。 */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
