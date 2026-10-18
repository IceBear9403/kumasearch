package com.kuma.search.common.constant;

/**
 * 请求追踪相关的常量。
 *
 * <p>放在 common 模块而不是 web 模块，是因为写入方与读取方位于不同的模块：
 * {@code RequestIdFilter}（web）负责生成并放进 MDC，
 * {@code SearchServiceImpl}（core）负责取出来写进 {@code t_search_log.request_id}。
 * 状态码之类的字符串一旦两边各写一份，改一处就会静默失配 ——
 * 失配的表现是"这一列永远查不到东西"，而不会有任何报错。
 */
public final class TraceConstants {

    private TraceConstants() {
    }

    /** MDC 中的键名，同时也是回写给调用方的响应头名称。 */
    public static final String REQUEST_ID = "requestId";

    /**
     * 上游（网关/负载均衡）可能已经生成了追踪 ID 并通过这个头传入。
     * 存在时沿用而不覆盖，这样跨服务的调用链才能串起来。
     */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 直接取值时的上限，防止调用方塞一个超长字符串进 MDC 与日志。 */
    public static final int MAX_REQUEST_ID_LENGTH = 64;
}
