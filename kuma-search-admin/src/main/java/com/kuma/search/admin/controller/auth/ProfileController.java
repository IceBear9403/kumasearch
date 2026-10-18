package com.kuma.search.admin.controller.auth;

import com.kuma.search.admin.dto.ProfileEmailRequest;
import com.kuma.search.admin.dto.ProfileNicknameRequest;
import com.kuma.search.admin.dto.ProfilePasswordRequest;
import com.kuma.search.admin.dto.TokenResponse;
import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.admin.security.RateLimitGuard;
import com.kuma.search.admin.security.RateLimitKeys;
import com.kuma.search.admin.service.ProfileService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人信息维护：改昵称、改密码、换绑邮箱。路由前缀挂在 {@code /api/auth} 下，与其他认证接口同族。
 *
 * <h3>为什么这里<b>没有</b> {@code @PreAuthorize}</h3>
 * 这三个动作的对象都是"当前登录用户自己"，不存在"能改谁"的权限问题 ——
 * 只要有令牌，能改的就只有自己那一行（{@code userId} 取自 SecurityContext，
 * 不接受任何传参）。再加一个权限码，只会多出一个"有权限但改不了任何人"的空壳。
 *
 * <p>但"没有权限注解"不等于"匿名可用"：{@code SecurityConfig} 的放行清单是逐条列举的，
 * 这三个路径都不在其中，因此走兜底的 {@code authenticated()}。
 * <b>新增端点时不要顺手把它们加进放行清单</b> —— 匿名改密码是一个不存在的需求。
 *
 * <h3>改密与换绑的响应体是 {@link TokenResponse}，不是空</h3>
 * 这两个动作会作废该用户的<b>全部</b>令牌（包括当前设备手上这枚），
 * 然后在作废水位线之后换发一对新的。前端拿到之后<b>必须覆盖本地令牌</b>，
 * 否则下一个请求就会 401 —— 而用户刚刚看到"修改成功"，体验上像是把功能做坏了。
 * 详细推理见 {@code AuthService#reissueAfterRevoke}。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/profile")
@RequiredArgsConstructor
@Tag(name = "个人信息", description = """
        当前登录用户改自己的昵称 / 密码 / 邮箱。需登录，无需额外权限码。

        改密码与换绑邮箱会**作废该用户的全部令牌**（把其它设备踢下线），
        同时给当前设备换发一对新令牌 —— 响应体是完整的 TokenResponse，
        **调用方必须用它覆盖本地已存的令牌**，否则后续请求会 401。

        换绑邮箱的验证码发往**新**邮箱（证明新邮箱归本人所有）。
        改密码按用户维度限流 5 次/小时。
        """)
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {

    private final ProfileService profileService;
    private final RateLimitGuard rateLimitGuard;
    private final KumaSearchProperties properties;

    /** 改昵称。不动作废、不换令牌：昵称不影响任何权限判断。 */
    @PutMapping("/nickname")
    @Operation(summary = "修改昵称", description = """
            只改展示用的昵称，**不影响登录态**：不动作废令牌、无需重新登录。
            长度 1~64；首尾空白会被去掉，去掉后为空则拒绝。""")
    public R<Void> nickname(@Valid @RequestBody ProfileNicknameRequest request) {
        profileService.changeNickname(requireUserId(), request.getNickname());
        return R.ok();
    }

    /**
     * 改密码。
     *
     * @return 换发的新令牌
     */
    @PutMapping("/password")
    @Operation(summary = "修改密码", description = """
            需要提供**当前密码**：这个接口只凭一枚令牌就能调，不验旧密码的话，
            任何拿到令牌的人都能直接把账号锁掉。

            新密码需通过强度校验（长度 8~128，且同时含字母与数字，与注册侧同一套规则），
            且不能与当前密码相同。

            ⚠️ 成功后会作废该用户的**全部**令牌（其它设备立即掉线，这正是改密码的目的），
            并给当前设备换发新令牌。**响应体是 TokenResponse，前端必须用它覆盖本地令牌**，
            否则当前设备也会立刻掉线。""")
    public R<TokenResponse> password(@Valid @RequestBody ProfilePasswordRequest request) {
        Long userId = requireUserId();
        limit(RateLimitKeys.DIM_PROFILE_PASSWORD_USER, userId, properties.getRateLimit().getProfilePassword());
        return R.ok(profileService.changePassword(userId, request));
    }

    /**
     * 换绑邮箱。
     *
     * @return 换发的新令牌
     */
    @PutMapping("/email")
    @Operation(summary = "换绑邮箱", description = """
            验证码发往**新邮箱**（先调 `POST /api/auth/email-code`，scene 传 `CHANGE_EMAIL`，
            该场景在发送时就要求新邮箱未被占用）。

            ⚠️ 验证码发到新邮箱而不是旧邮箱：发到新邮箱证明的是"新邮箱归你"，
            而这正是换绑要确立的事；发到旧邮箱只能证明"你收得到旧邮箱的信"，
            这件事由"你持有有效令牌"已经隐含了。

            成功后与改密码一样：作废全部令牌 + 换发当前设备的令牌，
            **响应体是 TokenResponse，前端必须覆盖本地令牌**。
            之后旧邮箱不能再用于邮箱验证码登录。""")
    public R<TokenResponse> email(@Valid @RequestBody ProfileEmailRequest request) {
        Long userId = requireUserId();
        limit(RateLimitKeys.DIM_PROFILE_EMAIL_USER, userId, properties.getRateLimit().getProfileEmail());
        return R.ok(profileService.changeEmail(userId, request));
    }

    // ==================================================================================
    //  内部
    // ==================================================================================

    /**
     * 按用户维度限流。
     *
     * <p>与 {@code ContentSubmitController} 不同，这两档<b>只配了用户维度</b>：
     * 要挡的是"猜某个账号的原密码 / 反复试换绑"，而这件事只与账号有关，
     * 加 IP 维度既挡不住换 IP 的爆破，又会让共用出口的正常用户互相顶掉额度。
     */
    private void limit(String dimension, Long userId, KumaSearchProperties.RateLimit.Limit limit) {
        rateLimitGuard.check(dimension, String.valueOf(userId), limit.getUserLimit(),
                limit.effectiveWindowSeconds(properties.getRateLimit().getWindowSeconds()));
    }

    /**
     * 取当前用户 ID，取不到就中断。
     *
     * <p>与 {@code AuthController} / {@code SearchController} 里的同名方法一样，
     * 刻意不抽公共方法（三处各三行，比一个所有人绕着走的抽象便宜）。
     *
     * <p>这三个接口的每一个写操作都以 userId 为唯一依据，为空时会把某一列写到
     * 主键为 null 的行上 —— 那是一次静默的失败，因此这个判断不能省，
     * 即使 Security 配置当前保证了必须登录。
     */
    private Long requireUserId() {
        Long userId = LoginUser.currentUserId();
        if (userId == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }
}
