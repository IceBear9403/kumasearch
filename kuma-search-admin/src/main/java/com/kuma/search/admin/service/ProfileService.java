package com.kuma.search.admin.service;

import com.kuma.search.admin.dto.EmailScene;
import com.kuma.search.admin.dto.ProfileEmailRequest;
import com.kuma.search.admin.dto.ProfilePasswordRequest;
import com.kuma.search.admin.dto.TokenResponse;
import com.kuma.search.admin.dto.UserInfoResponse;
import com.kuma.search.admin.security.EmailNormalizer;
import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.admin.security.PasswordPolicy;
import com.kuma.search.admin.security.TokenRevocationService;
import com.kuma.search.admin.security.UserDetailsServiceImpl;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.repository.entity.SysUser;
import com.kuma.search.repository.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 个人信息维护：改昵称、改密码、换绑邮箱。
 *
 * <h3>为什么这三个动作值得单独一个服务，而不是复用 {@code UserService}</h3>
 * {@code UserService} 是<b>管理员</b>管别人的入口：它能设 status、能改角色、能删号，
 * 调用方已经过了 {@code admin:user:manage}。本类是<b>用户管自己</b>：
 * 只能碰昵称/密码/邮箱三个字段，且必须自证身份（旧密码、新邮箱的验证码）。
 * 把两者塞进一个类，最直接的后果是"改自己"的方法离"能禁用任何人"的方法只有几行之遥 ——
 * 而这两个方法的鉴权强度差着一个数量级。
 *
 * <h3>三个动作对"令牌"的处理完全不同，这不是随意的</h3>
 * <table border="1">
 *   <caption>改什么，为什么</caption>
 *   <tr><th>动作</th><th>旧令牌</th><th>理由</th></tr>
 *   <tr>
 *     <td>改昵称</td>
 *     <td><b>不动</b></td>
 *     <td>昵称不在 access token 的 claim 里，也不参与任何权限判断。
 *         作废只会让用户莫名掉线，换不来任何安全收益。</td>
 *   </tr>
 *   <tr>
 *     <td>改密码</td>
 *     <td>全部作废 + 当前设备换发</td>
 *     <td>这正是用户改密码的目的：把别处的会话踢掉。而当前设备是他自己，
 *         一并踢掉会让他以为"改密码把自己弄下线了"。</td>
 *   </tr>
 *   <tr>
 *     <td>换绑邮箱</td>
 *     <td>全部作废 + 当前设备换发</td>
 *     <td>邮箱是账号定位的第二个入口（邮箱验证码登录、找回），换了它意味着
 *         凭证语境变了。作废的理由与改密码相同。</td>
 *   </tr>
 * </table>
 *
 * <p>「换发」的实现见 {@code AuthService#reissueAfterRevoke}，
 * 里面那个"签发时刻要推到水位线下一秒"的坑写在那里 —— 少看那一处，
 * 症状就是"改完密码当前设备立刻掉线"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserDetailsServiceImpl userDetailsService;
    private final TokenRevocationService tokenRevocationService;
    private final EmailCodeService emailCodeService;
    private final AuthService authService;

    // ==================================================================================
    //  读
    // ==================================================================================

    /**
     * 当前用户的完整信息，供 {@code GET /api/auth/me} 与个人中心回显。
     *
     * <h3>为什么要回查数据库，而不是直接用手上的 LoginUser</h3>
     * {@code LoginUser} 是 {@code JwtAuthFilter} 从 access token 的 claim 里拼出来的，
     * 而令牌里只有 userId / username / roles / perms —— <b>没有 nickname，也没有 email</b>。
     * 之前这里直接返回 claim 里的字段，于是 {@code /me} 的 nickname 恒为 null：
     * 登录响应里有昵称、刷新页面后昵称就没了。角色与权限仍然取自令牌（它们是本次请求的
     * 鉴权依据，必须与过滤器看到的完全一致），只有展示用字段回查。
     */
    public UserInfoResponse currentProfile(Long userId, LoginUser current) {
        SysUser user = requireUser(userId);
        return UserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                // 角色/权限取自令牌而非回查：它们是这次请求的鉴权依据，
                // 换一份来源就会出现"过滤器按 A 放行、响应里报 B"的分裂。
                .roles(current.getRoles())
                .permissions(current.getPermissions())
                .build();
    }

    // ==================================================================================
    //  写
    // ==================================================================================

    /**
     * 改昵称。
     *
     * <p>{@code @NotBlank} 已经挡住了空白输入，这里再 trim 一次并判空：
     * 校验注解在 DTO 层，"  " 这种只有空格但非空的输入仍会漏过来，
     * 而一个纯空格的昵称在界面上就是一个看不见的名字。
     */
    public void changeNickname(Long userId, String nickname) {
        String trimmed = nickname == null ? null : nickname.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw BizException.paramInvalid("nickname 不能为空");
        }
        SysUser patch = new SysUser();
        patch.setId(userId);
        patch.setNickname(trimmed);
        // 只更新这一列：updateById 会跳过 null 字段，因此不会碰到密码、邮箱、状态。
        sysUserMapper.updateById(patch);
        log.info("用户 {} 修改了昵称", userId);
    }

    /**
     * 改密码。
     *
     * <h3>顺序是有意的</h3>
     * <ol>
     *   <li><b>先验旧密码</b> —— 这个接口只凭一枚令牌就能调，不验旧密码的话，
     *       任何拿到令牌的人都能直接把账号锁掉（改成他不知道的密码），
     *       而"改密码"本该是用户夺回账号的动作；</li>
     *   <li>再判新旧相同 —— 否则"改成功了但密码没变"，用户下次还得再试一遍；</li>
     *   <li>最后过 {@code PasswordPolicy}，且<b>必须在 BCrypt 之前</b>：
     *       强度 12 的一次散列约 200~300ms，拿明显不合规的输入去消耗它是白送的计算资源。</li>
     * </ol>
     *
     * @return 换发的新令牌，当前设备继续使用
     */
    public TokenResponse changePassword(Long userId, ProfilePasswordRequest request) {
        SysUser user = requireUser(userId);

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            // 与登录侧同一个错误码：不区分"用户不存在 / 旧密码错误"，
            // 这里虽然身份已知，但沿用同一个码能让前端的错误处理只写一份。
            log.warn("修改密码时旧密码校验失败 userId={}", userId);
            throw BizException.of(ResultCode.BAD_CREDENTIALS);
        }
        // 只比字符串就够了，不需要再拿新密码去 passwordEncoder.matches 一次：
        // 上面那一步已经确认了"旧密码是对的"，也就是 oldPassword 就是当前密码，
        // 于是 newPassword != oldPassword 与 newPassword != 当前密码 等价。
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw BizException.paramInvalid("新密码不能与当前密码相同");
        }
        PasswordPolicy.validate(request.getNewPassword());

        SysUser patch = new SysUser();
        patch.setId(userId);
        patch.setPassword(passwordEncoder.encode(request.getNewPassword()));
        sysUserMapper.updateById(patch);

        tokenRevocationService.revokeUser(userId, "用户自助修改密码");
        log.info("用户 {} 修改了密码，已作废其全部令牌并换发当前设备的令牌", userId);
        return authService.reissueAfterRevoke(reload(userId));
    }

    /**
     * 换绑邮箱。
     *
     * <h3>为什么验证码发到新邮箱</h3>
     * 见 {@link ProfileEmailRequest} 的类注释：发到新邮箱证明的是"新邮箱归你"，
     * 而那正是换绑要确立的事；发到旧邮箱只能证明"你还收得到旧邮箱的信"，
     * 这件事由"你正持有有效令牌"已经隐含了。
     *
     * <p>占用检查在这里<b>再做一次</b>（发码时已经查过）：两个动作之间用户可以
     * 拿这个邮箱去注册另一个账号，而"同一个邮箱两个账号"会让邮箱验证码登录
     * 查出任意一行（见 {@code sql/schema.sql} 里 {@code uk_email} 的注释）。
     *
     * @return 换发的新令牌（邮箱参与账号定位，凭证语境变了）
     */
    public TokenResponse changeEmail(Long userId, ProfileEmailRequest request) {
        SysUser user = requireUser(userId);

        String normalized = EmailNormalizer.normalize(request.getNewEmail());
        if (normalized == null) {
            throw BizException.paramInvalid("newEmail 不能为空");
        }
        if (normalized.equals(user.getEmail())) {
            // 归一化之后再比：'A@x.com' 与 'a@x.com' 是同一个邮箱，
            // 不归一化就比会放过一次"改了个寂寞"的换绑，还白花一封邮件。
            throw BizException.paramInvalid("新邮箱与当前邮箱相同，无需修改");
        }
        // 同一个校验入口与注册侧共用，避免"两处各写一条 SQL、其中一处漏了归一化"。
        // 这里不需要排除自己：上面刚拒绝了"与当前邮箱相同"，所以命中的一定是别人的账号。
        if (userDetailsService.emailTaken(normalized)) {
            throw BizException.of(ResultCode.EMAIL_EXISTS);
        }

        // 校验新邮箱收到的码。放在占用检查之后：先告诉用户"这个邮箱被占了"，
        // 就不用白白消耗他一次验证码尝试（尝试次数达上限会让验证码整体作废）。
        emailCodeService.verifyCode(EmailScene.CHANGE_EMAIL, normalized, request.getEmailCode());

        SysUser patch = new SysUser();
        patch.setId(userId);
        patch.setEmail(normalized);
        sysUserMapper.updateById(patch);

        tokenRevocationService.revokeUser(userId, "用户自助换绑邮箱");
        log.info("用户 {} 换绑了邮箱，已作废其全部令牌并换发当前设备的令牌", userId);
        return authService.reissueAfterRevoke(reload(userId));
    }

    // ==================================================================================
    //  内部
    // ==================================================================================

    /**
     * 取用户行，不存在就当作未认证。
     *
     * <p>用 {@code UNAUTHORIZED} 而不是 {@code NOT_FOUND}：能走到这里说明令牌是有效的，
     * 而令牌有效却查不到人只有一种解释 —— 这个账号刚被删除。对用户而言
     * "你已不再登录"比"用户不存在"更准确，也不会把"这个 id 曾经存在"暴露出去。
     */
    private SysUser requireUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 重新装载 LoginUser。
     *
     * <p>重新查库而不是复用请求里那份：改完密码/邮箱后手上的 LoginUser 仍是旧的，
     * 而换发的令牌里要带<b>最新的</b>角色与权限。这里也顺带天然地拒绝了
     * "账号已被删除还在改邮箱"的情况。
     */
    private LoginUser reload(Long userId) {
        try {
            return userDetailsService.loadByUserId(userId);
        } catch (UsernameNotFoundException e) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
    }
}
