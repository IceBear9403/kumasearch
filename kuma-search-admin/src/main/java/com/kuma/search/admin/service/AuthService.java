package com.kuma.search.admin.service;

import com.kuma.search.admin.captcha.CaptchaVerifier;
import com.kuma.search.admin.dto.EmailLoginRequest;
import com.kuma.search.admin.dto.EmailScene;
import com.kuma.search.admin.dto.LoginRequest;
import com.kuma.search.admin.dto.RefreshRequest;
import com.kuma.search.admin.dto.RegisterRequest;
import com.kuma.search.admin.dto.TokenResponse;
import com.kuma.search.admin.security.EmailNormalizer;
import com.kuma.search.admin.security.JwtTokenProvider;
import com.kuma.search.admin.security.LoginAttemptGuard;
import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.admin.security.TokenRevocationService;
import com.kuma.search.admin.security.UserDetailsServiceImpl;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.repository.entity.SysUser;
import com.kuma.search.repository.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Objects;

/**
 * 认证编排：登录、刷新、登出。
 *
 * <h3>三处吊销入口，都收敛在 {@link TokenRevocationService}</h3>
 * <ul>
 *   <li><b>登出</b>：access token 与 refresh token 一并拉黑。
 *       只拉黑 access token 是不够的 —— refresh token 有效期 7 天，
 *       登出后它仍能换出一枚全新的 access token，等于"登出"没有发生。</li>
 *   <li><b>刷新轮换</b>：换发新令牌时立刻作废旧 refresh token，防止一枚 refresh token 被反复使用。</li>
 *   <li><b>刷新前校验用户级作废</b>：这一点容易漏。若只在过滤器里校验作废水位线，
 *       一个已被禁用的用户仍可以用旧 refresh token 换到一枚<b>全新签发</b>的 access token，
 *       而新令牌的 {@code iat} 晚于水位线、会被放行 —— 作废机制被自己绕过。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenRevocationService tokenRevocationService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final CaptchaVerifier captchaVerifier;
    private final EmailCodeService emailCodeService;
    private final RegisterService registerService;
    private final SysUserMapper sysUserMapper;

    /**
     * 账号密码登录。
     *
     * <p>用户名不存在与密码错误统一返回 {@code BAD_CREDENTIALS}，避免账号枚举。
     * <b>全程不得记录明文密码</b>。
     *
     * @param clientIp 来源 IP，仅用于登录失败限流，不落库、不返回给前端
     * @throws BizException 被限流/锁定（RATE_LIMITED）、凭据错误（BAD_CREDENTIALS）或账号被禁用（ACCOUNT_DISABLED）
     */
    public TokenResponse login(LoginRequest request, String clientIp) {
        String username = request.getUsername();

        verifyCaptchaForPasswordLogin(request.getCaptchaVerification());

        // 防爆破检查必须在校验密码之前：否则被锁定的账号每来一次请求仍要跑一遍 BCrypt，
        // 攻击者能用它做算力消耗型 DoS，"锁定"也就失去了意义。
        loginAttemptGuard.checkAllowed(username, clientIp);

        LoginUser user;
        try {
            user = (LoginUser) userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            // 用户名不存在也计数：否则"计数有没有涨"就成了一个账号是否存在的探针。
            loginAttemptGuard.recordFailure(username, clientIp);
            throw BizException.of(ResultCode.BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptGuard.recordFailure(username, clientIp);
            throw BizException.of(ResultCode.BAD_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            // 禁用账号不计入失败次数：它的所有者再试多少次都不会成功，
            // 继续计数只会让一个本就被禁用的账号多出一条"已锁定"的状态，没有任何防护价值。
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }

        // 登录成功清除账号维度的失败计数（不清 IP 维度，理由见 LoginAttemptGuard#reset）
        loginAttemptGuard.reset(username);
        touchLastLogin(user.getUserId());
        log.info("用户登录成功 username={} userId={}", user.getUsername(), user.getUserId());
        return issueTokens(user);
    }

    /**
     * 注册并直接登录。
     *
     * <p>「注册即登录」：注册成功后返回的令牌与 {@code /api/auth/login} <b>完全一致</b>。
     * 让用户注册完还要再输一次刚设的密码，是纯粹的多余步骤，而且不少人会在这一步
     * 因为记错刚设的密码而以为"注册失败了"。
     *
     * <p>用户的创建与角色授予在 {@link RegisterService} 里（含事务），
     * 令牌签发复用本类既有的 {@link #issueTokens} —— 这里刻意不自己拼
     * {@code TokenResponse}，理由见 {@code RegisterService#register} 的注释。
     */
    public TokenResponse register(RegisterRequest request, String clientIp) {
        Long userId = registerService.register(request, clientIp);

        // 重新装载而不是手工构造 LoginUser：角色与权限必须由 UserDetailsServiceImpl
        // 按"用户 → 角色 → 权限"的固定路径解析出来。手工构造会绕开那条路径，
        // 表现是新注册的用户能登录但所有权限为空 —— 而这条 bug 只在新增用户时才出现。
        LoginUser user = userDetailsService.loadByUserId(userId);
        touchLastLogin(userId);
        return issueTokens(user);
    }

    /**
     * 邮箱验证码登录。
     *
     * <h3>⚠️ 这条链路的响应必须与"邮箱是否存在"无关</h3>
     * 邮箱不存在与验证码错误返回<b>同一个错误码（{@code EMAIL_CODE_INVALID}）与同一条文案</b>。
     * 任何可区分的差异 —— 错误码、文案、甚至返回体的字段个数 —— 都会把这个接口
     * 变成"批量探测哪些邮箱是本站用户"的工具，而一个已备案、对外公开的服务的
     * 用户邮箱清单本身就是有价值的数据。
     *
     * <p>发码侧也做了同样的处理，见 {@code EmailCodeService} 的类注释：
     * 那里对不存在的邮箱会走完所有限流与配额检查，只是不真的发信。
     * <b>两处必须一起看</b>：只改一处，探测点就会从发码侧移到登录侧（或反过来）。
     *
     * <h3>⚠️ 邮箱码登录同样要走 LoginAttemptGuard</h3>
     * 少了这一步，新开的这条链路就是一条绕过防爆破的旁路 ——
     * 密码登录被锁死的账号，可以换个入口从邮箱码这边继续试。
     * account 维度用<b>邮箱</b>作 key（{@code AuthKeys#loginFailureByAccount} 的入参
     * 本就是"用户提交的原文"，邮箱在这里正好承担同样的角色）。
     *
     * <p>这也顺带解释了 {@code LoginAttemptGuard} 为什么值得被复用而不是重写：
     * 它同时管住了账号与 IP 两个维度，换一个入口时只是换 key 的取值。
     */
    public TokenResponse loginByEmail(EmailLoginRequest request, String clientIp) {
        captchaVerifier.verify(request.getCaptchaVerification());

        String email = EmailNormalizer.normalize(request.getEmail());
        if (email == null) {
            // 刻意不在这里说"邮箱为空"：那会成为一个与"邮箱不存在"可区分的响应。
            // 归一化后为空只可能来自空白输入，而 @NotBlank 已经挡住了绝大多数。
            throw BizException.of(ResultCode.EMAIL_CODE_INVALID);
        }

        loginAttemptGuard.checkAllowed(email, clientIp);

        LoginUser user;
        try {
            user = userDetailsService.loadByEmail(email);
        } catch (UsernameNotFoundException e) {
            // 邮箱不存在：同样计数（理由同密码登录 —— 计数涨不涨不能成为"邮箱是否存在"的探针），
            // 但错误码与验证码错误<b>完全一致</b>。
            loginAttemptGuard.recordFailure(email, clientIp);
            throw BizException.of(ResultCode.EMAIL_CODE_INVALID);
        }

        emailCodeService.verifyCode(EmailScene.LOGIN, email, request.getEmailCode());

        if (!user.isEnabled()) {
            // 与密码登录一致：禁用账号不计数（再试多少次都不会成功，计数没有防护价值），
            // 但会返回一个可区分的错误。这里与"邮箱枚举"不冲突 ——
            // 能走到这一行说明请求方已经持有了发往该邮箱的有效验证码，
            // 他本来就知道这个账号存在。
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }

        loginAttemptGuard.reset(email);
        touchLastLogin(user.getUserId());
        log.info("用户通过邮箱验证码登录成功 userId={}", user.getUserId());
        return issueTokens(user);
    }

    /**
     * 密码登录的滑块校验 —— 注意这里与注册/邮箱码登录的<b>不对称</b>，是有意的。
     *
     * <p>另两条链路调用的是 {@code captchaVerifier.verify(...)}，验证码未启用时会
     * 直接拒绝服务；而这里未启用时<b>放行</b>。理由在于两条链路与这块功能的先后关系：
     * <ul>
     *   <li>注册 / 邮箱码登录是<b>随本次改造新增</b>的入口，滑块是它们流程的一部分。
     *       "尚未配置"意味着"这个新功能还没开通"，正确的表现是拒绝服务；
     *       反过来"未配置就跳过"会让防线在配置遗漏时静默消失。</li>
     *   <li>密码登录是<b>既有且正在工作</b>的入口。若这里也 fail-closed，
     *       那么升级代码之后（默认 {@code kuma.captcha.enabled=false}）
     *       管理员会在配置好滑块之前完全登不进后台 ——
     *       一次由配置默认值引起的停服，而配置文件本身没有任何错误。
     *       这条链路在本层加入之前就是可用的，未启用时它只是回到原状。</li>
     * </ul>
     * 换句话说：<b>新增入口的滑块是不可选的；既有入口的滑块是可选的加固。</b>
     * 一旦 {@code kuma.captcha.enabled=true}，两者就完全一致了 —— 都校验，且都不给绕过余地。
     */
    private void verifyCaptchaForPasswordLogin(String captchaVerification) {
        if (!captchaVerifier.isEnabled()) {
            return;
        }
        captchaVerifier.verify(captchaVerification);
    }

    /**
     * 更新最后登录时间。
     *
     * <p><b>失败只记日志、不影响登录。</b>这一列是审计信息，
     * 而"审计字段没写成功"远不该导致"用户登不进来"。
     * 用一条仅更新单列的 UPDATE，不整行覆盖 —— 整行更新会把并发的其它字段修改一起冲掉。
     */
    private void touchLastLogin(Long userId) {
        try {
            SysUser patch = new SysUser();
            patch.setId(userId);
            patch.setLastLoginTime(LocalDateTime.now());
            sysUserMapper.updateById(patch);
        } catch (RuntimeException e) {
            log.warn("更新最后登录时间失败，不影响本次登录：userId={} err={}", userId, e.getMessage());
        }
    }

    /**
     * 用 refresh token 换取新令牌。
     *
     * <p>校验 refresh token 自身（签名、未过期、类型为 refresh、不在黑名单、未被用户级作废），
     * 再<b>重新查库</b>装载最新角色与权限 —— 保证续期后不遗留旧权限。
     * 成功后旧 refresh token 立即加入黑名单，实现刷新轮换，防长期复用。
     *
     * @throws BizException 令牌过期（TOKEN_EXPIRED）、无效或已吊销（TOKEN_INVALID）、账号被禁用（ACCOUNT_DISABLED）
     */
    public TokenResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = tokenProvider.parseClaims(request.getRefreshToken());
        } catch (ExpiredJwtException e) {
            throw BizException.of(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }
        Long userId = tokenProvider.userId(claims);
        if (!tokenProvider.isRefreshToken(claims)
                || userId == null
                || tokenRevocationService.isBlacklisted(claims.getId())
                || tokenRevocationService.isUserRevoked(userId, claims.getIssuedAt())) {
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }
        LoginUser user;
        try {
            user = userDetailsService.loadByUserId(userId);
        } catch (UsernameNotFoundException e) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        if (!user.isEnabled()) {
            throw BizException.of(ResultCode.ACCOUNT_DISABLED);
        }
        // 轮换：旧 refresh token 立即作废
        tokenRevocationService.blacklist(claims);
        return issueTokens(user);
    }

    /**
     * 登出：把 access token 与该请求携带的 refresh token 一并加入黑名单。
     *
     * <p>令牌已失效、无法解析或类型不对时静默跳过，登出幂等、不因令牌问题报错 ——
     * 登出失败却把用户留在登录态，是比"少拉黑一枚令牌"更糟的结果。
     *
     * @param rawAccessToken  来自 {@code Authorization} 头；为 null 时只处理 refresh token
     * @param rawRefreshToken 来自请求体；为 null 时只处理 access token（兼容不带 body 的老客户端）
     */
    public void logout(String rawAccessToken, String rawRefreshToken) {
        Claims accessClaims = parseQuietly(rawAccessToken, "access");
        if (accessClaims != null) {
            tokenRevocationService.blacklist(accessClaims);
        }

        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        Claims refreshClaims = parseQuietly(rawRefreshToken, "refresh");
        if (refreshClaims == null) {
            return;
        }
        if (!tokenProvider.isRefreshToken(refreshClaims)) {
            // 传进来的其实是 access token。不拉黑：它多半已经在上一步处理过了，
            // 而把一个 access token 的黑名单记录用在这里，会掩盖前端传错字段的问题。
            log.warn("登出请求体的 refreshToken 实际上不是 refresh 类型，已忽略");
            return;
        }
        // 只允许作废"属于本次登录用户"的 refresh token。
        // 少了这一句，任何持有他人 refresh token 的调用者都能把它拉黑 ——
        // 虽然拿得到令牌的人本就无需这么绕，但没有理由为此开一个跨用户写的口子。
        if (accessClaims != null
                && !Objects.equals(tokenProvider.userId(refreshClaims), tokenProvider.userId(accessClaims))) {
            log.warn("登出请求携带的 refreshToken 不属于当前登录用户，已忽略");
            return;
        }
        tokenRevocationService.blacklist(refreshClaims);
        log.info("登出完成，已作废 access 与 refresh 两枚令牌，userId={}",
                tokenProvider.userId(refreshClaims));
    }

    /**
     * 解析令牌，失败只记 DEBUG 不抛异常。
     *
     * @param kind 仅用于日志区分是哪个字段解析失败
     */
    private Claims parseQuietly(String rawToken, String kind) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        try {
            return tokenProvider.parseClaims(rawToken);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("登出时 {} 令牌已失效或不可解析，跳过：{}", kind, e.getMessage());
            return null;
        }
    }

    private TokenResponse issueTokens(LoginUser user) {
        return issueTokens(user, new Date());
    }

    /**
     * 换发令牌：用户刚把自己作废掉，但当前这台设备要继续用。
     *
     * <h3>为什么需要这个方法，而不是"改完密码再调一次登录"</h3>
     * 改密码 / 换绑邮箱都会调用 {@code revokeUser}（那是必须的：作废其它设备上的会话
     * 正是用户改密码时想要的效果），而当前设备手上的令牌也一起被作废了。
     * 让用户为此重新登录一次，体验上等于"改密码把自己踢下线了" ——
     * 那不是用户的本意，他会认为改密功能坏了。所以换发。
     *
     * <h3>签发时刻必须由 {@link TokenRevocationService} 给出</h3>
     * 见 {@code TokenRevocationService#issuableDateAfterRevoke}：水位线是毫秒、
     * {@code iat} 只有秒，直接用 {@code new Date()} 签发出来的令牌会被刚写的记录判为已作废。
     * 这个换算刻意收在吊销服务里 —— 把它留在调用方，每个新增的"作废+换发"场景
     * 都要重新踩一次同一个坑。
     *
     * @param user 重新装载过的用户（角色/权限必须是最新的，不能用手上旧令牌里的那份）
     */
    public TokenResponse reissueAfterRevoke(LoginUser user) {
        Date issuedAt = tokenRevocationService.issuableDateAfterRevoke(user.getUserId());
        log.info("作废后换发令牌 userId={} 签发时刻={}", user.getUserId(), issuedAt);
        return issueTokens(user, issuedAt);
    }

    /**
     * 按指定签发时刻发一对令牌。
     *
     * <p>access 与 refresh <b>必须传同一个时刻</b>：只把 access 推到水位线之后的话，
     * 换发出来的 refresh token 仍会被判为已作废，症状是「两小时内一切正常，
     * 之后续期突然失败」—— 比当场掉线更难查。两处都用同一个 {@code issuedAt} 是刻意的。
     */
    private TokenResponse issueTokens(LoginUser user, Date issuedAt) {
        JwtTokenProvider.GeneratedToken access = tokenProvider.generateAccessToken(user, issuedAt);
        JwtTokenProvider.GeneratedToken refresh = tokenProvider.generateRefreshToken(user, issuedAt);
        return TokenResponse.builder()
                .accessToken(access.token())
                .refreshToken(refresh.token())
                .expiresIn(access.expiresInSeconds())
                .userId(user.getUserId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .roles(user.getRoles())
                .permissions(user.getPermissions())
                .build();
    }
}
