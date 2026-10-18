package com.kuma.search.admin.security;

import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 登录用户主体，既作为 {@link UserDetails} 参与认证，也作为请求上下文里的 principal。
 *
 * <p>它同时携带权限码与角色码：方法级鉴权只用{@code 权限码}（如 {@code admin:index:manage}），
 * 角色码统一加 {@code ROLE_} 前缀保留，仅供 {@code hasRole} 之类的粗粒度判断，二者不要混用。
 */
@Getter
@Builder
public class LoginUser implements UserDetails {

    /** 角色权限前缀，Spring Security 的 {@code hasRole('ADMIN')} 约定。 */
    public static final String ROLE_PREFIX = "ROLE_";

    private final Long userId;
    private final String username;
    /** BCrypt 散列。仅登录校验时使用，任何响应体与日志都不得输出。 */
    private final String password;
    private final String nickname;
    private final List<String> roles;
    private final List<String> permissions;
    /** 账号状态：1 启用、0 禁用；为 null 时按启用处理。 */
    private final Integer status;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (permissions != null) {
            for (String perm : permissions) {
                if (perm != null && !perm.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(perm));
                }
            }
        }
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
                }
            }
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == null || status == 1;
    }

    /**
     * 取当前 SecurityContext 中的登录用户。
     *
     * @return 已登录返回用户对象，匿名/未认证返回 {@code null}（调用方自行决定是否放行）
     */
    public static LoginUser current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    /** 取当前登录用户 ID，未登录返回 {@code null}。 */
    public static Long currentUserId() {
        LoginUser loginUser = current();
        return loginUser == null ? null : loginUser.getUserId();
    }
}
