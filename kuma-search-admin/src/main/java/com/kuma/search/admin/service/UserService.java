package com.kuma.search.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kuma.search.admin.security.PasswordPolicy;
import com.kuma.search.admin.security.TokenRevocationService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.PageResult;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.repository.entity.SysRole;
import com.kuma.search.repository.entity.SysUser;
import com.kuma.search.repository.entity.SysUserRole;
import com.kuma.search.repository.mapper.SysRoleMapper;
import com.kuma.search.repository.mapper.SysUserMapper;
import com.kuma.search.repository.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 用户管理（admin 专有）。
 *
 * <p>贯穿本类的两条铁律：
 * <ul>
 *   <li>密码只以 BCrypt 散列入库，读接口返回前必须清空 {@code password} 字段；</li>
 *   <li>任何日志都不得打印明文密码（故日志只记 username / userId）。</li>
 * </ul>
 *
 * <p><b>第三条：所有会改变"这个用户还能做什么"的写操作，都必须同时作废他的令牌。</b>
 * 禁用、删除、改密码、改角色都属于这一类。漏掉任何一处，权限模型就会在这条路径上失效 ——
 * 而且失效是静默的：旧 access token 会一直可用到自然过期（最长 2 小时），
 * refresh token 更是能撑到 7 天。作废水位线的机制见 {@code TokenRevocationService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;

    /**
     * 分页查询用户。
     *
     * <p><b>返回前把 password 置空</b> —— 密码散列绝不能出现在列表响应里。
     */
    public PageResult<SysUser> page(int pageNum, int pageSize, String keyword) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getNickname, keyword));
        }
        wrapper.orderByDesc(SysUser::getId);
        Page<SysUser> result = sysUserMapper.selectPage(page, wrapper);
        result.getRecords().forEach(user -> user.setPassword(null));
        return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    /**
     * 新建用户：密码 BCrypt 加密后入库，用户名唯一。
     *
     * @throws BizException 用户名为空、密码为空或不合规、用户名已存在
     */
    public Long create(SysUser user) {
        if (!StringUtils.hasText(user.getUsername())) {
            throw BizException.paramInvalid("username 不能为空");
        }
        if (!StringUtils.hasText(user.getPassword())) {
            throw BizException.paramInvalid("password 不能为空");
        }
        // 强度校验必须在 encode 之前，且与注册侧、自助改密侧共用同一份规则。
        // 在本次改动之前这里是空的：管理员能在后台造出 "123456" 的账号 ——
        // 一条比注册侧宽得多的旁路，会把全站的密码口径撕开一个口子，
        // 而"某个账号的密码很弱"这件事在事后是看不出来的（库里只有散列）。
        PasswordPolicy.validate(user.getPassword());

        Long exists = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, user.getUsername()));
        if (exists != null && exists > 0) {
            throw BizException.of(ResultCode.USERNAME_EXISTS);
        }
        user.setId(null);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        sysUserMapper.insert(user);
        log.info("新建用户成功 username={} userId={}", user.getUsername(), user.getId());
        return user.getId();
    }

    /**
     * 更新用户。
     *
     * <p>仅当请求携带非空密码时才重新加密；否则把 password 置 null，
     * 让 MyBatis-Plus 跳过该列，避免把已有密码覆盖成空。
     * 改密时同样要过 {@code PasswordPolicy} —— 见 {@link #create} 里那段说明，
     * "后台能设弱密码"这个口子在这两个方法上各开一次，只堵一个没有用。
     *
     * <p><b>改密码或禁用账号时，同步作废该用户的全部令牌。</b>
     * 两件事都意味着"之前的凭证不应再代表这个人"：
     * 改密码后旧令牌仍能用的意思是"改了密码也踢不掉已经进来的会话"，
     * 而这恰恰是用户改密码时最想达到的效果。
     * 只改昵称之类不影响权限的字段<b>不</b>作废 —— 那会让用户莫名其妙被登出。
     */
    public void update(SysUser user) {
        if (user.getId() == null) {
            throw BizException.paramInvalid("id 不能为空");
        }
        boolean passwordChanged = StringUtils.hasText(user.getPassword());
        if (passwordChanged) {
            PasswordPolicy.validate(user.getPassword());
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null);
        }
        sysUserMapper.updateById(user);

        // 注意判断条件用的是**入参里的 status**，不是「状态发生了变化」：
        // 查旧值再比对新旧状态要多一次查询，而这里把"请求里带了 status=0"直接当作禁用处理，
        // 结果是一样的 —— 重复禁用同一个用户再作废一次令牌，代价只是多一次 Redis SET。
        boolean disabled = Integer.valueOf(0).equals(user.getStatus());
        if (passwordChanged || disabled) {
            tokenRevocationService.revokeUser(user.getId(),
                    passwordChanged ? "密码已被修改" : "账号已被禁用");
        }
        log.info("更新用户成功 userId={} 改密={} 禁用={}", user.getId(), passwordChanged, disabled);
    }

    /**
     * 删除用户。
     *
     * <p>删的是逻辑删除，令牌却必须<b>真</b>作废：{@code JwtAuthFilter} 校验的是令牌自身，
     * 并不会每次请求都回查 {@code t_sys_user}，所以一个被逻辑删除的用户
     * 手上的令牌依然畅通无阻。
     */
    public void delete(Long id) {
        sysUserMapper.deleteById(id);
        tokenRevocationService.revokeUser(id, "账号已被删除");
        log.info("删除用户 userId={}", id);
    }

    /**
     * 给用户分配角色：先删该用户全部角色关联，再按入参重建（覆盖式）。
     *
     * <p><b>分配完必须作废令牌。</b>角色与权限码是写在 access token 的 claim 里的，
     * 校验时直接读 claim、不回查数据库 —— 这是"无状态鉴权"的代价：
     * 不作废的话，被收回 ADMIN 角色的用户，手上的旧令牌仍会带着 ADMIN 权限继续用，
     * 最长 2 小时。也就是说"降权"在这段时间内完全不生效。
     *
     * <p>清空角色（{@code roleIds} 为空）同样要作废，而且更要作废：那是一次彻底的降权。
     * 因此这里<strong>不能</strong>沿用"没有变化就早返回"的优化。
     *
     * @param roleIds 新角色 ID 列表；为空表示清空该用户所有角色
     */
    public void assignRoles(Long userId, List<Long> roleIds) {
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        List<Long> validRoleIds = roleIds == null ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long roleId : validRoleIds) {
            sysUserRoleMapper.insert(SysUserRole.builder().userId(userId).roleId(roleId).build());
        }
        tokenRevocationService.revokeUser(userId, "角色/权限已变更");
        log.info("分配角色成功 userId={} roleIds={}", userId, validRoleIds);
    }

    /** 查询用户当前拥有的角色。 */
    public List<SysRole> rolesOf(Long userId) {
        List<Long> roleIds = sysUserRoleMapper.selectList(
                        new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getId, roleIds));
    }
}
