package com.kuma.search.web.filter;

import com.kuma.search.common.constant.TraceConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求分配一个追踪 ID，放进 MDC 并回写到响应头。
 *
 * <h3>为什么需要它</h3>
 * 这个类的存在是为了兑现一个已经写进文档与表结构的承诺：
 * {@code t_search_log.request_id} 这一列被描述为"与日志中的 traceId 对应，用于定位单次请求"，
 * 但在没有这个过滤器之前，**没有任何地方给它赋过值，它恒为 NULL**。
 * 一列永远查不到东西的"追踪字段"比没有这一列更糟 —— 排查线上问题时，
 * 人会先去查它，查到一片空，然后再开始怀疑数据没落库。
 *
 * <h3>为什么放在 web 模块而不是 core</h3>
 * 它是 Servlet 层的关注点（依赖 {@code HttpServletRequest}）。core 模块不依赖 Spring Web，
 * 这样将来把搜索能力单独部署成一个服务时，core 不需要为了一个过滤器把 web 栈也带上。
 * core 侧只通过 {@link MDC} 取值，两侧靠 {@link TraceConstants} 里的键名对齐。
 *
 * <h3>为什么不生成一个 TraceContext 传递</h3>
 * 那需要把参数一路穿到 service 层，改动面覆盖每个方法签名。MDC 是线程绑定的，
 * 天然在这里设置、在任何地方读取，代价是必须配一个能输出它的日志格式
 * （见 application.yml 的 {@code logging.pattern.level} 与 {@code logging.pattern.console}）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);

        MDC.put(TraceConstants.REQUEST_ID, requestId);
        // 回写响应头：前端报错时可以把这一串贴给运维，直接定位到那一次的日志。
        response.setHeader(TraceConstants.HEADER_REQUEST_ID, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理。Tomcat 复用工作线程，不清理的话下一个请求会带上上一个请求的 ID ——
            // 这比自己生成一个错的更危险：日志看起来是完整的，只是指向了另一次请求。
            MDC.remove(TraceConstants.REQUEST_ID);
        }
    }

    /**
     * 优先沿用上游传来的 ID，没有才自己生成。
     *
     * <p>沿用上游是必要的：网关或另一个服务已经生成了 ID 时，如果这里另起一个，
     * 一次跨服务调用会在各自的日志里留下两串对不上的 ID，链路就断了。
     *
     * <p>取到的值要截断并过滤：这个头是<b>客户端可控</b>的，
     * 塞一个几千字符的串进来会污染每一条日志（并放大日志体积），
     * 塞换行符则能在日志里伪造出额外的行。
     */
    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(TraceConstants.HEADER_REQUEST_ID);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }

        String trimmed = incoming.trim();
        if (trimmed.length() > TraceConstants.MAX_REQUEST_ID_LENGTH) {
            trimmed = trimmed.substring(0, TraceConstants.MAX_REQUEST_ID_LENGTH);
        }
        // 只保留 ASCII 可见字符之外的字符会被替换掉，杜绝换行符伪造日志行。
        return trimmed.replaceAll("[^\\x21-\\x7E]", "_");
    }
}
