package com.kuma.search.admin.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kuma.search.repository.entity.SysPermission;
import com.kuma.search.repository.entity.SysRole;
import com.kuma.search.repository.entity.SysRolePermission;
import com.kuma.search.repository.entity.SysUser;
import com.kuma.search.repository.entity.SysUserRole;
import com.kuma.search.repository.mapper.SysPermissionMapper;
import com.kuma.search.repository.mapper.SysRoleMapper;
import com.kuma.search.repository.mapper.SysRolePermissionMapper;
import com.kuma.search.repository.mapper.SysUserMapper;
import com.kuma.search.repository.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 从数据库装载用户、角色与权限。
 *
 * <p>权限装载路径（缺一环权限就为空，务必按顺序）：
 * {@code t_sys_user} → {@code t_sys_user_role} → {@code t_sys_role} →
 * {@code t_sys_role_permission} → {@code t_sys_permission.perm_code}。
 *
 * <p>用多次 {@code selectList} 而非写 XML join：关系链固定、数据量小，
 * 多查几次换来的可读性与可测试性比一条复杂 SQL 更划算。
 *
 * <p>{@code ADMIN} 角色自动拥有全部权限码 —— 这样新加一个权限码时，
 * 超管无需在库里补关联即可生效，也不会因为漏配关联把管理员锁在门外。
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    /** 管理员角色码。 */
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * 普通用户角色码。注册用户自动获得这个角色。
     *
     * <p>按 {@code role_code} 查而不是写死 {@code id=2}：{@code sql/data.sql} 里的 id
     * 只是一个自增主键，任何一次重新初始化数据都可能让它不是 2，
     * 而"注册用户拿到了管理员角色"这种错误不会有任何提示。
     */
    public static final String ROLE_USER = "USER";

    /**
     * 兜底权限码：即使 {@code t_sys_permission} 未初始化，ADMIN 也能访问全部后台接口。
     *
     * <p>⚠️ 这是一份「全部权限码」的清单，<b>新增权限码时必须同步加进来</b> ——
     * 漏加的表现是「权限表被人清空过（或新库还没跑 data.sql）时，管理员用不了那个接口」，
     * 而正常环境下完全复现不出来。
     */
    private static final List<String> BUILTIN_PERMISSIONS = List.of(
            "search:read",
            "admin:content:manage",
            "admin:index:manage",
            "admin:sync:manage",
            "admin:user:manage",
            "admin:dict:manage",
            "content:submit");

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return buildLoginUser(user);
    }

    /**
     * 按用户 ID 装载用户，供 refresh 时重新读取最新角色与权限（保证续期后权限不过期）。
     *
     * @throws UsernameNotFoundException 用户不存在或已被删除
     */
    public LoginUser loadByUserId(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return buildLoginUser(user);
    }

    /**
     * 按邮箱装载用户，供邮箱验证码登录使用。
     *
     * <p><b>传入的邮箱必须已经过归一化</b>（trim + 小写，见 {@code EmailNormalizer}）——
     * 这里不做归一化，因为"谁负责归一化"必须只有一个答案：写入与查询两侧都在调用点做，
     * 而这个方法同时服务于两者，它自己再归一化一次会掩盖调用点漏做的事实。
     *
     * <p>{@code email} 列上建了 {@code uk_email(email, deleted)} 唯一索引，
     * 因此 {@code selectOne} 不会因重复而抛异常。注意逻辑删除的行不参与匹配 ——
     * 这正是唯一键里带上 {@code deleted} 的效果（见 {@code sql/schema.sql}）：
     * 一个用户被删除后，他的邮箱可以被重新注册。
     *
     * @throws UsernameNotFoundException 邮箱不存在
     */
    public LoginUser loadByEmail(String email) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email));
        if (user == null) {
            throw new UsernameNotFoundException("邮箱不存在");
        }
        return buildLoginUser(user);
    }

    /**
     * 该邮箱是否已被占用。
     *
     * <p>供注册流程使用。返回 boolean 而不是抛异常，是因为调用方对"占用"的处理
     * 与"用户不存在"完全不同（前者是业务规则，后者是异常路径）。
     */
    public boolean emailTaken(String email) {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getEmail, email)) > 0;
    }

    private LoginUser buildLoginUser(SysUser user) {
        List<String> roleCodes = loadRoleCodes(user.getId());
        List<String> permissions = loadPermissionCodes(user.getId(), roleCodes);
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .nickname(user.getNickname())
                .roles(roleCodes)
                .permissions(permissions)
                .status(user.getStatus())
                .build();
    }

    private List<String> loadRoleCodes(Long userId) {
        List<Long> roleIds = roleIdsOf(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getId, roleIds))
                .stream()
                .map(SysRole::getRoleCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> loadPermissionCodes(Long userId, List<String> roleCodes) {
        if (roleCodes.contains(ROLE_ADMIN)) {
            // ADMIN：以权限表为准并并入内置兜底码
            Set<String> all = new LinkedHashSet<>(BUILTIN_PERMISSIONS);
            sysPermissionMapper.selectList(new LambdaQueryWrapper<SysPermission>())
                    .stream()
                    .map(SysPermission::getPermCode)
                    .filter(Objects::nonNull)
                    .forEach(all::add);
            return new ArrayList<>(all);
        }

        List<Long> roleIds = roleIdsOf(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = sysRolePermissionMapper.selectList(
                        new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return sysPermissionMapper.selectList(
                        new LambdaQueryWrapper<SysPermission>().in(SysPermission::getId, permissionIds))
                .stream()
                .map(SysPermission::getPermCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private List<Long> roleIdsOf(Long userId) {
        return sysUserRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
