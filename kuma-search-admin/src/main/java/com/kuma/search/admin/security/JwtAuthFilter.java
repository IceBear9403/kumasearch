package com.kuma.search.admin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器：从 {@code Authorization: Bearer xxx} 解析出身份写入 SecurityContext。
 *
 * <p>校验顺序（缺一不可）：
 * <ol>
 *   <li>签名与有效期 —— 由 jjwt 在解析时完成；</li>
 *   <li>令牌类型必须是 {@code access}，用 refresh token 直接访问业务接口一律拒绝；</li>
 *   <li>必须<b>不在 Redis 黑名单</b>（登出后立即失效的关键一步）；</li>
 *   <li>必须<b>没有踩到用户级作废水位线</b>——即令牌的 {@code iat} 不早于该用户最近一次
 *       作废时刻（禁用账号、改密码、变更角色时写入）。缺了这一条，
 *       "禁用用户"要等 access token 自然过期才生效，最长 2 小时。</li>
 * </ol>
 *
 * <p>第 3、4 步是两次 Redis 查询。没有合并成一次 {@code multiGet}，
 * 是因为两者语义不同、失效原因不同（单枚令牌 vs 整个用户），合并后日志与排查时无法区分；
 * 而 Redis 的一次 GET 在本地网络是亚毫秒级，相对本次请求必然要走的 ES 检索可以忽略。
 *
 * <p>本过滤器<b>不抛异常</b>：任何校验失败都只是"不写入上下文"，后续由 AuthorizationFilter /
 * 异常处理器统一回 401，避免过滤器里泄露解析细节。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationService tokenRevocationService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = tokenProvider.parseClaims(token);
                if (isUsableAccessToken(claims)) {
                    SecurityContextHolder.getContext().setAuthentication(buildAuthentication(claims, request));
                } else {
                    log.debug("JWT 校验未通过：类型非 access、已进入黑名单或已被用户级作废");
                }
            } catch (ExpiredJwtException e) {
                log.debug("JWT 已过期：{}", e.getMessage());
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("JWT 无效：{}", e.getMessage());
            } catch (RuntimeException e) {
                // 例如 Redis 不可用（黑名单与作废水位线都在 Redis 里）。
                // 这里刻意 fail-closed：宁可把所有人挡在门外，也不放行一个无法确认是否已被吊销的令牌。
                // 代价是 Redis 一挂就全站 401，排查的人很可能先怀疑令牌而不是缓存 ——
                // 所以这条日志必须是 WARN 且带上原始原因，让根因一眼可见。
                log.warn("校验令牌吊销状态时发生异常，按未认证处理（fail-closed）：{}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(tokenProvider.header());
        if (header == null) {
            return null;
        }
        String prefix = tokenProvider.tokenPrefix();
        if (prefix != null && !prefix.isBlank()) {
            return header.startsWith(prefix) ? header.substring(prefix.length()).trim() : null;
        }
        return header.trim();
    }

    /**
     * 令牌是否可用于本次请求：类型正确，且既不在黑名单、也没被用户级作废。
     *
     * <p>拆成一个方法是为了让这个"与"关系只有一处定义 —— 三个条件散在 {@code if} 里时，
     * 后续加第四个条件（比如"令牌必须来自当前允许的设备"）很容易漏掉其中一个分支。
     */
    private boolean isUsableAccessToken(Claims claims) {
        return tokenProvider.isAccessToken(claims)
                && !tokenRevocationService.isBlacklisted(claims.getId())
                && !tokenRevocationService.isUserRevoked(tokenProvider.userId(claims), claims.getIssuedAt());
    }

    private UsernamePasswordAuthenticationToken buildAuthentication(Claims claims, HttpServletRequest request) {
        List<String> permissions = tokenProvider.permissions(claims);
        List<String> roles = tokenProvider.roles(claims);

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String perm : permissions) {
            if (perm != null && !perm.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(perm));
            }
        }
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(LoginUser.ROLE_PREFIX + role));
            }
        }

        LoginUser principal = LoginUser.builder()
                .userId(tokenProvider.userId(claims))
                .username(tokenProvider.username(claims))
                .roles(roles)
                .permissions(permissions)
                .status(1)
                .build();

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
