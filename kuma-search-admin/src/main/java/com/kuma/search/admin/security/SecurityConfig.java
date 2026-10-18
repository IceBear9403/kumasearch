package com.kuma.search.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置：无状态 JWT + 方法级权限码鉴权。
 *
 * <p>放行清单只包含登录/注册/检索等<b>确实需要匿名可达</b>的端点，其余一律
 * {@code authenticated()}，{@code /api/admin/**} 再由 {@code @PreAuthorize}
 * 收紧到具体权限码（默认拒绝）。
 *
 * <h3>⚠️ 兜底必须是 {@code anyRequest().authenticated()}，永远不要改成 {@code permitAll()}</h3>
 * 搜索改成匿名可用之后，这里会显得「反正检索接口都放行了，不如整个放开省事」。
 * <b>不能这样做，理由是安全边界会随代码漂移：</b>
 * <ul>
 *   <li>兜底「默认拒绝」时，新写的 Controller <b>默认是受保护的</b>。
 *       忘记加 {@code @PreAuthorize} 的后果是「接口能用但只有登录用户能用」—— 一个立刻会被发现的 bug。</li>
 *   <li>兜底 {@code permitAll()} 时，新写的 Controller <b>默认是公开的</b>。
 *       忘记加注解的后果是「管理接口对全网开放」，而且<b>没有任何提示</b> ——
 *       不会有报错、不会有日志，只会在某天被人发现。</li>
 * </ul>
 * 也就是说这个改动把「配置错误」的表现从「功能不可用」换成了「静默的越权暴露」。
 * 放行清单继续逐条列举，且<b>每个公开端点都要有对应的 {@code RateLimitGuard} 调用</b>
 * （见 {@code SearchController}），这才是匿名化的正确代价。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    /**
     * 允许跨域的**前端源**（scheme + host + port，不含路径），逗号分隔，
     * 可通过 {@code kuma.cors.allowed-origins} 覆盖。默认放开本地前端 dev server 5173
     * （后端自身在 18082，别把两者搞混）。
     * 生产环境请显式列举受信前端域名，切勿使用 {@code *}。
     *
     * <p>注意：前端在开发期经 Vite proxy、生产期经 Nginx 反代访问接口，浏览器看到的是同源请求，
     * 根本不走 CORS —— 这份配置只在「前端绕过代理、直接请求后端端口」时才生效。
     * 所以它配错不会立刻暴露，改前端端口时容易被漏掉。
     */
    @Value("${kuma.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    /** 密码编码器：BCrypt，强度 12（与 docs/06-security.md 一致）。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 全局禁用 CSRF：本项目身份完全来自 Authorization: Bearer 头（无状态 JWT），
                // 不存在"浏览器自动携带 Cookie 凭证"的前提，CSRF 攻击面不存在。
                // 注意：一旦把 refresh token 改为 Cookie 下发，就必须重新评估此处的关闭是否仍安全。
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 预检请求放行（CORS 需要）
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录与刷新是匿名入口
                        .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                        // 注册与邮箱验证码：注册本身必须匿名，验证码是注册的前置步骤
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/email-code",
                                "/api/auth/login/email").permitAll()
                        // 图形验证码：取图与滑块校验都发生在登录之前，必须匿名
                        .requestMatchers("/api/captcha/**").permitAll()
                        // 检索的公开端点。**逐条列举，不用 /api/search/** 通配**
                        // —— 通配会把后加的 /api/search/history 也一起放出去，
                        // 而那个接口天生属于个人数据。
                        .requestMatchers(HttpMethod.POST, "/api/search").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/search/web").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/search/suggest",
                                "/api/search/hot",
                                // 类型选择器的数据源。它只返回 typeCode + typeName，
                                // **不要**因为"反正只是读类型"就把 /api/admin/index-type 也放行 ——
                                // 那个接口带着 field_config / search_config，是索引的完整 mapping。
                                "/api/search/types").permitAll()
                        // ⚠️ 刻意不放行 /api/search/history（GET 与 DELETE）：
                        // 它是个人搜索历史，Controller 上仍留着 @PreAuthorize，
                        // 兜底也保持 authenticated()，两道都在。
                        // 接口文档（Swagger UI）。
                        //
                        // ⚠️ 放行这三条【不等于】生产环境上存在这些端点：真正决定生死的是
                        // kuma.swagger.enabled（默认 false）。关掉时 springdoc 不注册任何
                        // handler，请求落到 NoResourceFoundException，被 GlobalExceptionHandler
                        // 包成 HTTP 200 + {"code":10001}（**不是 404** —— 本项目所有业务失败都走
                        // HTTP 200，判断开关看响应体里有没有 openapi 字段）。
                        // 放行只是让"页面的静态资源与 JSON"不必再单独持一个令牌，因为浏览器
                        // 直接打开 /swagger-ui.html 时无法先登录再取页面本身。
                        // 换句话说：这里的风险由开关兜底，不由这里兜底。
                        //
                        // 为什么不"用登录来保护文档页"：那样 Authorize 按钮就成了一枚
                        // 死锁 —— 要打开页面得先有令牌，而拿令牌要先进页面去调登录接口。
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // 健康探针
                        .requestMatchers("/actuator/health/**").permitAll()
                        // 错误转发的内部端点，放行以免异常被误判成 401
                        .requestMatchers("/error").permitAll()
                        // 其余全部要求已认证，管理端再由 @PreAuthorize 收权限码
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, R.fail(ResultCode.UNAUTHORIZED)))
                        .accessDeniedHandler((request, response, e) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, R.fail(ResultCode.ACCESS_DENIED))))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 显式列举来源，不使用 *（allowCredentials=true 时通配符本就非法）
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * 关闭 {@link JwtAuthFilter} 的 Servlet 容器级自动注册。
     *
     * <p>它已是 {@code @Component}（Filter Bean 会被 Boot 自动挂到 {@code /*}），
     * 但我们只在 Security 过滤链里使用它；若不在此显式禁用，会被注册两次、每个请求执行两遍。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter jwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    /** 过滤器层（未进入控制器）的认证/授权失败也统一返回 {@code R} 结构，保证前端解析一致。 */
    private void writeJson(HttpServletResponse response, int status, R<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
