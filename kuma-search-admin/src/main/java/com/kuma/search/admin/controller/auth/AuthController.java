package com.kuma.search.admin.controller.auth;

import com.kuma.search.admin.dto.EmailCodeRequest;
import com.kuma.search.admin.dto.EmailLoginRequest;
import com.kuma.search.admin.dto.HistoryImportRequest;
import com.kuma.search.admin.dto.LoginRequest;
import com.kuma.search.admin.dto.LogoutRequest;
import com.kuma.search.admin.dto.RefreshRequest;
import com.kuma.search.admin.dto.RegisterRequest;
import com.kuma.search.admin.dto.TokenResponse;
import com.kuma.search.admin.dto.UserInfoResponse;
import com.kuma.search.admin.security.JwtTokenProvider;
import com.kuma.search.admin.security.LoginUser;
import com.kuma.search.admin.service.AuthService;
import com.kuma.search.admin.service.EmailCodeService;
import com.kuma.search.admin.service.ProfileService;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.R;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.service.HotWordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录（密码 / 邮箱码）、注册、验证码、刷新、登出、当前用户、历史导入。
 *
 * <p>放行清单见 {@code SecurityConfig}：{@code /api/auth/login}、{@code /refresh}、
 * {@code /register}、{@code /email-code}、{@code /login/email} 匿名可访问；
 * {@code /logout}、{@code /me}、{@code /history/import} 需登录。
 *
 * <p>⚠️ 放行清单是<b>逐条列举</b>的，不是 {@code /api/auth/**}。
 * 通配会把后加的 {@code /history/import} 一起放出去，而那个接口天生属于个人数据 ——
 * 这正是「兜底保持 {@code authenticated()}」之外的第二道防线。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录（密码 / 邮箱码）、注册、验证码、刷新、登出、当前用户、历史导入")
public class AuthController {

    private final AuthService authService;
    private final EmailCodeService emailCodeService;
    private final JwtTokenProvider tokenProvider;
    private final HotWordService hotWordService;
    /** 只用于 {@code /me} 的组装（昵称与邮箱要回查数据库，见该端点注释）。改自己走 {@code ProfileController}。 */
    private final ProfileService profileService;

    /**
     * 账号密码登录。匿名可访问。
     *
     * <p>坑：用户名不存在与密码错误返回同一错误码，勿在此区分，否则可被用来枚举账号。
     *
     * <p>连续失败会被锁定并返回 {@code RATE_LIMITED}（10003），阈值见 {@code kuma.auth.login.*}。
     */
    @PostMapping("/login")
    @Operation(summary = "密码登录",
            description = "匿名可访问。连续登录失败会触发锁定限流（错误码 10003）。")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                  HttpServletRequest servletRequest) {
        return R.ok(authService.login(request, clientIp(servletRequest)));
    }

    /**
     * 发送邮箱验证码。匿名可访问。
     *
     * <p>校验顺序（滑块 → IP 限流 → 场景校验 → 节流 → 配额）与每一步的理由
     * 都在 {@code EmailCodeService} 的类注释里。这里只强调最容易被忽略的一条：
     * <b>{@code scene} 决定响应策略</b> —— {@code REGISTER} 会明确告知邮箱已注册，
     * {@code LOGIN} 则无论邮箱是否存在都返回相同结果。
     *
     * <p>返回体固定为已发送时的结果。注意 <b>{@code scene=LOGIN} 且邮箱不存在时
     * 也会返回这个结果，而实际并未发信</b> —— 这不是 bug，见上面那条。
     */
    @PostMapping("/email-code")
    @Operation(summary = "发送邮箱验证码",
            description = """
                    匿名可访问。需先通过滑块校验（先取滑块图、再校验滑块），并受发码节流与配额限制。
                    scene=LOGIN 时，即使邮箱不存在也返回「已发送」，实际不会发信。""")
    public R<Void> sendEmailCode(@Valid @RequestBody EmailCodeRequest request,
                                 HttpServletRequest servletRequest) {
        emailCodeService.sendCode(request, clientIp(servletRequest));
        return R.ok();
    }

    /**
     * 注册。匿名可访问。<b>注册即登录</b>，返回体与 {@link #login} 完全一致。
     */
    @PostMapping("/register")
    @Operation(summary = "注册",
            description = """
                    匿名可访问。注册成功即登录，返回体与密码登录完全一致。
                    需先获取邮箱验证码并通过滑块校验。""")
    public R<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                     HttpServletRequest servletRequest) {
        return R.ok(authService.register(request, clientIp(servletRequest)));
    }

    /**
     * 邮箱验证码登录。匿名可访问。返回体与 {@link #login} 完全一致。
     *
     * <p>坑：<b>邮箱不存在与验证码错误必须返回同一个错误码与同一条文案</b>，
     * 见 {@code AuthService#loginByEmail}。改动这条链路时不要"顺手"把错误信息写得更具体。
     */
    @PostMapping("/login/email")
    @Operation(summary = "邮箱验证码登录",
            description = """
                    匿名可访问。emailCode 必须是 LOGIN 场景发出的验证码，注册场景的码不通用。
                    邮箱不存在与验证码错误返回相同错误码与文案。""")
    public R<TokenResponse> loginByEmail(@Valid @RequestBody EmailLoginRequest request,
                                         HttpServletRequest servletRequest) {
        return R.ok(authService.loginByEmail(request, clientIp(servletRequest)));
    }

    /**
     * 用 refresh token 换新令牌。匿名可访问（凭 refreshToken 自身校验）。
     *
     * <p>坑：refresh token 只用于换令牌，不可当作 access token 调用业务接口（过滤器会拒绝）。
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌",
            description = """
                    匿名可访问（凭请求体中的 refreshToken 校验）。
                    refreshToken 只能用于换新令牌，不能当作 accessToken 调用业务接口。""")
    public R<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(authService.refresh(request));
    }

    /**
     * 登出：把当前 access token 与请求体里的 refresh token 一并加入 Redis 黑名单。需登录。
     *
     * <p>坑：必须把 refresh token 也传上来。它的有效期是 7 天，而 access token 只有 2 小时 ——
     * 只拉黑 access token 的话，登出后攻击者（或共用设备的下一个人）拿 refresh token
     * 就能换出一枚新的 access token，登出形同虚设。请求体可以为空，但那样就没有真正结束会话。
     *
     * <p>黑名单 TTL 取令牌剩余有效期，令牌自然过期后记录自动清除；令牌不可解析时幂等返回。
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录",
            description = """
                    需登录。请求体可为空，但只有带上 refreshToken 才能一并拉黑它、真正结束会话。
                    令牌不可解析时幂等返回成功。""")
    @SecurityRequirement(name = "bearerAuth")
    public R<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) LogoutRequest body) {
        authService.logout(stripPrefix(authorization), body == null ? null : body.getRefreshToken());
        return R.ok();
    }

    /**
     * 返回当前登录用户信息，不含密码。需登录。
     *
     * <p>组装在 {@code ProfileService#currentProfile} 里，不在这里拼 —— 昵称与邮箱
     * <b>不在令牌里</b>（见 {@code UserInfoResponse} 的类注释），直接返回 claim 里的字段
     * 会让 {@code /me} 的昵称恒为 null：登录响应里有昵称、刷新页面后就没了。
     */
    @GetMapping("/me")
    @Operation(summary = "取当前用户",
            description = """
                    需登录。返回当前登录用户信息，不含密码。
                    含邮箱（本人）—— 个人中心要回显它。""")
    @SecurityRequirement(name = "bearerAuth")
    public R<UserInfoResponse> me() {
        LoginUser current = LoginUser.current();
        if (current == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return R.ok(profileService.currentProfile(current.getUserId(), current));
    }

    /**
     * 导入浏览器本地的搜索历史。需登录。
     *
     * <h3>为什么是"用户主动发起的一次导入"</h3>
     * 未登录时存在浏览器里的搜索词是<b>匿名行为</b>。自动合并到账号，等于把匿名行为
     * 与实名账号静默关联起来 —— 而用户对"没登录时搜的东西"有合理的不同预期，
     * 他当时的预期很可能正是"这不会留在我的账号上"。因此前端在登录成功后弹一次提示，
     * 由用户决定是否导入。<b>这个决定属于用户，不属于实现。</b>
     *
     * <p>坑一：{@code userId} 只能取当前登录用户，绝不接受请求传入的
     * —— 否则就是一个"往别人历史里写词"的越权接口。
     *
     * <p>坑二：导入<b>只写个人历史，不写热词榜</b>。见
     * {@code HotWordService#importHistory}：热词榜反映的是当下的检索热度，
     * 一次导入会让 50 个从未被"搜索"过的词各 +1，等于凭空造出一批热词。
     */
    @PostMapping("/history/import")
    @Operation(summary = "导入搜索历史",
            description = """
                    需登录。导入到当前登录用户，只写个人历史、不影响热词榜。
                    重复导入安全：同一关键词只会覆盖时间戳，不产生重复条目。""")
    @SecurityRequirement(name = "bearerAuth")
    public R<Void> importHistory(@Valid @RequestBody HistoryImportRequest request) {
        hotWordService.importHistory(requireUserId(), request.getTypeCode(), request.getKeywords());
        return R.ok();
    }

    /**
     * 取客户端 IP，仅用于登录失败限流。
     *
     * <p><b>只采信 {@code RemoteAddr}，刻意不读 X-Forwarded-For。</b>
     * 那个头是客户端可以随便写的，采信它等于把"按 IP 限流"的开关交给攻击者 ——
     * 每次请求换一个伪造值就能换一个全新的计数桶，限流彻底失效。
     *
     * <p>代价是：应用部署在 Nginx / 网关后面时，{@code RemoteAddr} 是所有客户端共用的代理地址，
     * 于是 IP 维度的计数变成全局共享。要让这里拿到真实客户端 IP，
     * 必须让 Servlet 容器自己（而不是应用代码）去信任代理头：
     * 配置 {@code server.tomcat.remoteip.remote-ip-header} 与 {@code internal-proxies}，
     * 它只会信任来自内网代理的转发头。详见 {@code docs/06-security.md}。
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * 取当前登录用户 ID，未登录直接抛 {@code UNAUTHORIZED}。
     *
     * <p>与 {@code SearchController#requireUserId} 是同一段代码，<b>刻意没有抽成公共方法</b>：
     * 抽出去需要一个"当前用户工具类"，而 {@code LoginUser} 已经是那个类了 ——
     * 再包一层只会让"用户 ID 从哪来"多一个跳转，且将来想给某个控制器加特例
     * （例如允许内部调用传 userId）时，公共方法就成了必须绕开的东西。
     * 两处各三行，比一个所有人都绕着走的抽象便宜。
     *
     * <p>注意：即使 Security 配置里 {@code /history/import} 需要认证，
     * 这个判断也不能省。配置是会变的（比如将来被某个更宽的通配放行），
     * 而"从 SecurityContext 取不到用户时不要继续"这条约束必须由代码自己保证。
     */
    private Long requireUserId() {
        Long userId = LoginUser.currentUserId();
        if (userId == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String stripPrefix(String authorization) {
        if (authorization == null) {
            return null;
        }
        String prefix = tokenProvider.tokenPrefix();
        if (StringUtils.hasText(prefix) && authorization.startsWith(prefix)) {
            return authorization.substring(prefix.length()).trim();
        }
        return authorization.trim();
    }
}
