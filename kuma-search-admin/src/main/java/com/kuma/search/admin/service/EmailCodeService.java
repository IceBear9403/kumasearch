package com.kuma.search.admin.service;

import com.kuma.search.admin.captcha.CaptchaVerifier;
import com.kuma.search.admin.dto.EmailCodeRequest;
import com.kuma.search.admin.dto.EmailScene;
import com.kuma.search.admin.mail.EmailCodeTemplate;
import com.kuma.search.admin.mail.MailMessage;
import com.kuma.search.admin.mail.MailSender;
import com.kuma.search.admin.security.AuthKeys;
import com.kuma.search.admin.security.EmailNormalizer;
import com.kuma.search.admin.security.RateLimitGuard;
import com.kuma.search.admin.security.RateLimitKeys;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import com.kuma.search.repository.entity.SysUser;
import com.kuma.search.repository.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;

/**
 * 邮箱验证码的发送与校验。
 *
 * <h3>验证码只存 Redis，不建表</h3>
 * TTL 5 分钟、一次性消费，这类数据放进 MySQL 只会带来"需要一张清理表"的负担。
 * 项目里已有同样的先例（登录锁定 {@code LoginAttemptGuard} 也是走 Redis 不落库），
 * 而 {@code t_search_history} 那张"建了但没有任何写入代码的死表"
 * 正是"先把表建上"这个习惯的代价。
 *
 * <h3>⚠️ 邮箱枚举防护：两个场景的响应策略<b>不同</b>，这是刻意的</h3>
 * <table border="1">
 *   <caption>同一个接口在两种场景下的不同响应</caption>
 *   <tr><th>场景</th><th>邮箱已注册时</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@code REGISTER}</td>
 *     <td>明确报 {@code EMAIL_EXISTS}</td>
 *     <td>注册页必须告诉用户"这个邮箱已注册，去登录"。这里的可探测性是无法避免的：
 *         即便不提示，用户提交完整注册表单后依然会得到"邮箱已被注册"。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code LOGIN}</td>
 *     <td><b>返回与成功完全相同的响应</b></td>
 *     <td>否则这个接口就是"批量探测哪些邮箱是本站用户"的工具。
 *         已备案、对外公开的服务，用户邮箱清单本身就是有价值的目标。</td>
 *   </tr>
 * </table>
 *
 * <p><b>而"返回相同响应"远不止是"返回同一个 code"。</b>下面这些都会泄露信息，
 * 因此在 {@code LOGIN} 场景里对不存在的邮箱也必须走完同样的流程：
 * <ul>
 *   <li>限流与配额检查照常执行 —— 若不执行，攻击者只要反复探测同一个不存在的邮箱，
 *       发现"它永远不会被限流"，就等于确认了邮箱不存在；</li>
 *   <li>同邮箱发码节流照常写入 —— 同理，可观察的节流差异就是一个探测信号；</li>
 *   <li>只有<b>真正调发信 API</b> 这一步被跳过（不发一封给不存在地址的邮件，
 *       那是纯粹的费用与退信率）。</li>
 * </ul>
 * 也就是说：<b>状态照常变更，只是不发信。</b>
 */
@Slf4j
@Service
public class EmailCodeService {

    /** 6 位数字验证码的取值范围。 */
    private static final int CODE_BOUND = 1_000_000;

    /**
     * 验证码的随机源。
     *
     * <p>用 {@link SecureRandom} 而不是 {@code Math.random()} / {@code Random}：
     * 后两者的种子可预测、序列可枚举，攻击者只要知道足够多的历史验证码
     * 就能推算下一个 —— 而验证码的全部安全性都建立在"不可预测"上。
     */
    private final SecureRandom random = new SecureRandom();

    private final StringRedisTemplate stringRedisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final KumaSearchProperties properties;
    private final SysUserMapper sysUserMapper;
    private final CaptchaVerifier captchaVerifier;
    private final RateLimitGuard rateLimitGuard;

    /**
     * 发信器用 {@link ObjectProvider} 懒解析。
     *
     * <p>{@code kuma.auth.email.enabled=false} 时 {@code MailSender} 的 Bean
     * <b>根本不存在</b>（两个实现都是条件装配的），直接构造注入会让应用启动失败
     * —— 而"关掉邮箱功能"不应该让整个应用起不来。
     *
     * <p>之所以不干脆提供一个"什么都不做"的兜底实现：那会让"忘了配凭据"
     * 表现为"接口返回成功但邮件没发出去"，而这是最糟的一类故障。
     * 现在这样，{@link #sendMail} 会在真正需要发信时抛出一个明确的配置错误。
     */
    private final ObjectProvider<MailSender> mailSenderProvider;

    /** HTML 正文的渲染器。与发信器无关，因此走普通注入（它总是存在）。 */
    private final EmailCodeTemplate emailCodeTemplate;

    public EmailCodeService(StringRedisTemplate stringRedisTemplate,
                           PasswordEncoder passwordEncoder,
                           KumaSearchProperties properties,
                           SysUserMapper sysUserMapper,
                           CaptchaVerifier captchaVerifier,
                           RateLimitGuard rateLimitGuard,
                           ObjectProvider<MailSender> mailSenderProvider,
                           EmailCodeTemplate emailCodeTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.sysUserMapper = sysUserMapper;
        this.captchaVerifier = captchaVerifier;
        this.rateLimitGuard = rateLimitGuard;
        this.mailSenderProvider = mailSenderProvider;
        this.emailCodeTemplate = emailCodeTemplate;
    }

    // ==================================================================================
    //  发送
    // ==================================================================================

    /**
     * 发送验证码。
     *
     * <p><b>校验顺序本身就是安全要求，不能调整：</b>
     * <ol>
     *   <li><b>总开关</b> —— 未启用直接拒绝，不做任何后续动作；</li>
     *   <li><b>滑块</b> —— 必须排在任何"会产生费用或暴露信息"的动作之前。
     *       这个接口的每次成功调用都是一封真金白银的邮件，不先过滑块，
     *       邮件额度就是一个谁都能刷的免费攻击面；</li>
     *   <li><b>IP 限流</b> —— 滑块能挡住无脑脚本，挡不住打码平台。
     *       限流是滑块之后的第一层兜底，按 1 小时窗口计（分钟级窗口挡不住"每分钟刷满一轮"）；</li>
     *   <li><b>场景化校验</b> —— 只有 REGISTER 场景才检查邮箱是否已注册，理由见类注释；</li>
     *   <li><b>同邮箱节流</b> —— 防"同一个邮箱被反复轰炸"。这不是安全问题而是<b>体感与信誉问题</b>：
     *       用户连点几次就收到十几封邮件，接下来会把我们标记为垃圾邮件发送方，
     *       而发信域名信誉一掉，<b>所有用户</b>的验证码都会进垃圾箱；</li>
     *   <li><b>单邮箱日配额 → 全局日配额</b> —— 最后一道是成本闸，见
     *       {@code AuthKeys#emailGlobalQuota}：前两道都拦不住"换邮箱"的分布式刷量。</li>
     * </ol>
     *
     * @param clientIp 客户端 IP，用于限流。取自 {@code getRemoteAddr()}，不是 XFF，
     *                 见 {@code SearchController#clientIp}
     * @throws BizException 上述任一环节不通过
     */
    public void sendCode(EmailCodeRequest request, String clientIp) {
        KumaSearchProperties.Auth.Email email = requireEnabled();

        EmailScene scene = EmailScene.parse(request.getScene());
        String normalized = EmailNormalizer.normalize(request.getEmail());
        if (normalized == null) {
            throw BizException.paramInvalid("email 不能为空");
        }

        // 1. 滑块。一次性凭证，校验通过即被消费（见 CaptchaVerifier）。
        captchaVerifier.verify(request.getCaptchaVerification());

        // 2. IP 限流。窗口 1 小时，见 kuma.rate-limit.email-code。
        rateLimitGuard.check(RateLimitKeys.DIM_EMAIL_CODE_IP, clientIp,
                properties.getRateLimit().getEmailCode().getIpLimit(),
                properties.getRateLimit().getEmailCode()
                        .effectiveWindowSeconds(properties.getRateLimit().getWindowSeconds()));

        // 3. 场景化校验。
        //    REGISTER 与 CHANGE_EMAIL 是同一个要求（这个邮箱必须**没人用**），
        //    放在同一个分支里而不是各写一遍：两者的区别只在"谁来用"，不在校验规则。
        //    LOGIN 反过来 —— 邮箱不存在时它照常走完配额与计数，只是不发信（见下方），
        //    因为「这个邮箱是不是本站用户」不能从响应里被看出来。
        boolean exists = emailExists(normalized);
        if ((scene == EmailScene.REGISTER || scene == EmailScene.CHANGE_EMAIL) && exists) {
            throw BizException.of(ResultCode.EMAIL_EXISTS);
        }

        // 4~6. 三道配额闸。注意它们在 LOGIN 场景下对"邮箱不存在"也照常执行，
        //      这正是类注释里说的"状态照常变更，只是不发信"。
        checkSendThrottle(normalized);
        checkDailyQuota(email, normalized);

        if (scene == EmailScene.LOGIN && !exists) {
            // 邮箱不存在：走完了全部检查与计数，但不发信、不写验证码。
            // 对外表现与真实发送完全一致（同一个成功响应）。
            log.info("邮箱码登录请求的邮箱不存在，已跳过实际发信（对外响应与成功一致）");
            return;
        }

        String code = generateCode();
        storeCode(scene, normalized, code, email);
        sendMail(scene, normalized, code, email);
    }

    // ==================================================================================
    //  校验
    // ==================================================================================

    /**
     * 校验验证码，通过后<b>立即删除</b>（一次性消费）。
     *
     * <p>失败会计数，达 {@code kuma.auth.email.max-attempts}（默认 5）次时
     * <b>连验证码本身一起删除</b>：只拒绝当次请求是不够的，
     * 攻击者可以在同一枚码上继续试到 100 万种可能全部试完。删掉之后他必须重新触发发码，
     * 而那受同邮箱 60 秒节流与每日配额约束 —— 于是爆破速率被压到远低于 100 万 / 5 分钟。
     *
     * <p>另外，BCrypt 比对本身每次约 200~300ms（强度 12），这也在物理上限制了
     * 单位时间内能尝试的次数。两重限制叠加，"6 位数字"才够用 ——
     * 单独的 6 位数字在 5 分钟窗口里是<b>不够</b>的。
     *
     * @throws BizException 验证码不存在、过期、错误，或超出失败次数
     */
    public void verifyCode(EmailScene scene, String rawEmail, String code) {
        requireEnabled();

        String normalized = EmailNormalizer.normalize(rawEmail);
        if (normalized == null || !StringUtils.hasText(code)) {
            throw BizException.of(ResultCode.EMAIL_CODE_INVALID);
        }

        String codeKey = AuthKeys.emailCode(scene.name(), normalized);
        String stored = stringRedisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            // 过期、从未发送、或已被消费。三种情况对外不可区分 —— 这是好事：
            // 区分它们会让"这个邮箱刚才发过码吗"变成一个可探测的问题。
            throw BizException.of(ResultCode.EMAIL_CODE_INVALID);
        }

        String attemptKey = AuthKeys.emailCodeAttempt(scene.name(), normalized);
        int maxAttempts = properties.getAuth().getEmail().getMaxAttempts();

        if (!passwordEncoder.matches(code, stored)) {
            Long attempts = stringRedisTemplate.opsForValue().increment(attemptKey);
            // 计数 key 的 TTL 必须跟着验证码的剩余生命走，这里统一按配置值续期即可
            stringRedisTemplate.expire(attemptKey,
                    Duration.ofSeconds(properties.getAuth().getEmail().getCodeTtlSeconds()));
            if (attempts != null && attempts >= maxAttempts) {
                // 达上限：验证码与计数一起作废，攻击者必须重新发码（而发码受节流与配额约束）
                stringRedisTemplate.delete(codeKey);
                stringRedisTemplate.delete(attemptKey);
                log.warn("验证码失败次数达上限，已作废：scene={}", scene);
            }
            throw BizException.of(ResultCode.EMAIL_CODE_INVALID);
        }

        // 校验通过：立即删除。删除与"放行"必须是同一步 ——
        // 先返回成功再去删，中间那段时间就是一枚可被重放的验证码。
        stringRedisTemplate.delete(codeKey);
        stringRedisTemplate.delete(attemptKey);
    }

    // ==================================================================================
    //  内部
    // ==================================================================================

    /**
     * 检查邮箱功能是否启用，并返回配置。
     *
     * <p>关闭时<b>拒绝服务</b>而不是降级成"不校验验证码"。验证码是这三条链路的凭证本身，
     * 没有它就无所谓"校验通过"。这与 {@code CaptchaVerifier} 未启用时的处理是同一个原则。
     */
    private KumaSearchProperties.Auth.Email requireEnabled() {
        KumaSearchProperties.Auth.Email email = properties.getAuth().getEmail();
        if (!email.isEnabled()) {
            throw BizException.of(ResultCode.SYSTEM_ERROR,
                    "邮箱验证码功能未开通（kuma.auth.email.enabled=false）");
        }
        return email;
    }

    private boolean emailExists(String normalizedEmail) {
        return sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmail, normalizedEmail)) > 0;
    }

    /**
     * 同邮箱发码节流。
     *
     * <p>用 {@code SET NX} 一次完成"判断 + 占用"：拆成两步的话，用户连点两次
     * （这在真实场景里是常态，不是攻击）会让两个请求双双通过检查、双双发信。
     */
    private void checkSendThrottle(String normalizedEmail) {
        int interval = properties.getAuth().getEmail().getSendIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(
                AuthKeys.emailSendThrottle(normalizedEmail), "1", Duration.ofSeconds(interval));
        if (!Boolean.TRUE.equals(first)) {
            throw BizException.of(ResultCode.EMAIL_SEND_TOO_FREQUENT);
        }
    }

    /**
     * 单邮箱日配额与全局日配额。
     *
     * <p>两道都<b>先检查再自增</b>，且检查与自增不是原子的 —— 这意味着并发下
     * 有可能超出上限一两封。这是刻意接受的：把它做成原子的需要一段 Lua 同时管两个 key，
     * 而超出一两封的代价远小于那段脚本出错的代价（它一旦写错，后果是配额<b>永远</b>算不对）。
     * 真正的兜底是全局上限本身留了 25% 余量。
     */
    private void checkDailyQuota(KumaSearchProperties.Auth.Email email, String normalizedEmail) {
        String dateSuffix = AuthKeys.dateSuffix(LocalDate.now());

        String emailKey = AuthKeys.emailDailyQuota(normalizedEmail, dateSuffix);
        Long perEmail = readCount(emailKey);
        if (perEmail >= email.getDailyLimitPerEmail()) {
            throw BizException.of(ResultCode.EMAIL_QUOTA_EXCEEDED);
        }

        String globalKey = AuthKeys.emailGlobalQuota(dateSuffix);
        Long global = readCount(globalKey);
        if (global >= email.getGlobalDailyLimit()) {
            // 全局上限被打穿说明有人正在换邮箱刷量，或者运维手工发了很多信。
            // 这是必须告警的信号，因此用 ERROR 而不是 WARN。
            log.error("全局邮件日配额已用尽：used={} limit={}", global, email.getGlobalDailyLimit());
            throw BizException.of(ResultCode.EMAIL_QUOTA_EXCEEDED);
        }

        incrementWithTtl(emailKey);
        incrementWithTtl(globalKey);
    }

    private Long readCount(String key) {
        String raw = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // key 被手工改成了非数字。当作 0 继续，而不是让整个发码功能挂掉 ——
            // 此时最多是当天配额重算一次，比"用户完全收不到验证码"轻得多。
            log.warn("配额 key 的值不是数字，按 0 处理：key={}", key);
            return 0L;
        }
    }

    private void incrementWithTtl(String key) {
        stringRedisTemplate.opsForValue().increment(key);
        // TTL 固定 36 小时而不是"到当天 24 点"：日期后缀已经进了 key，
        // 跨天自然从 0 开始，因此 TTL 只是清理手段、不参与限流逻辑（见 AuthKeys#quotaTtlSeconds）。
        stringRedisTemplate.expire(key, Duration.ofSeconds(AuthKeys.quotaTtlSeconds()));
    }

    /**
     * 生成 6 位数字验证码，含前导零。
     *
     * <p>用 {@code nextInt(bound)} 而不是 {@code nextInt() % bound}：
     * 取模会引入偏差（{@code nextInt()} 的值域不是 bound 的整数倍），
     * 让低位数字出现得更频繁 —— 把有效熵从一个正好够用的值又削掉一截。
     * 格式化补零则保证 {@code 000123} 是一个合法的验证码，
     * 否则实际可用空间的十分之一被浪费掉。
     */
    private String generateCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }

    /**
     * 写入验证码：存 BCrypt 散列，不存明文。
     *
     * <p>验证码只有 6 位、生命周期 5 分钟，看起来"泄露了也没关系"。
     * 但 Redis 的 RDB/AOF 会被备份、会被运维拷走排查问题、可能被误提交进代码库。
     * <b>"一份快照泄露 = 一批正在生效的验证码泄露"这个等式不该成立</b>，
     * 而让它不成立的代价只是校验时多做一次 BCrypt 比对。
     */
    private void storeCode(EmailScene scene, String normalizedEmail, String code,
                           KumaSearchProperties.Auth.Email email) {
        stringRedisTemplate.opsForValue().set(
                AuthKeys.emailCode(scene.name(), normalizedEmail),
                passwordEncoder.encode(code),
                Duration.ofSeconds(email.getCodeTtlSeconds()));

        // 上一次的发码把失败计数一并清掉：留着它会让新验证码一上来就"已经失败过 N 次"，
        // 用户还有 1 次机会就被作废，而那次失败很可能属于上一枚已经过期的码。
        stringRedisTemplate.delete(AuthKeys.emailCodeAttempt(scene.name(), normalizedEmail));
    }

    private void sendMail(EmailScene scene, String normalizedEmail, String code,
                          KumaSearchProperties.Auth.Email email) {
        MailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            // 启用了邮箱功能却没有 MailSender 实现。抛异常而不是静默成功：
            // 静默的后果是"接口说发送成功、用户永远收不到"。
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件服务未配置，请联系管理员");
        }

        if (email.isLogCode()) {
            // 仅本地开发用。生产打开等于把验证码写进日志文件，
            // 任何有日志读取权限的人都能登录任意邮箱账号 —— 因此这条也打了 ERROR 级别，
            // 让它在日志里显眼到不会被忽略。
            log.error("【仅限本地开发】kuma.auth.email.log-code=true，验证码明文：email={} scene={} code={}",
                    normalizedEmail, scene, code);
        }

        int minutes = Math.max(1, email.getCodeTtlSeconds() / 60);
        // 先问发信方支不支持内联图片，再渲染：模板据此决定页头是"图标 + 站点名"
        // 还是"只有站点名"。反过来做的话，DirectMail 那条链路上会发出一个永远灰框的 <img>。
        EmailCodeTemplate.Content content =
                emailCodeTemplate.render(scene, code, minutes, sender.supportsInlineImages());

        sender.send(MailMessage.builder()
                .toAddress(normalizedEmail)
                .subject(String.format(email.getSubjectTemplate(), scene.label()))
                .textBody(buildTextBody(minutes, scene, code))
                .htmlBody(content.htmlBody())
                .inlineImages(content.inlineImages())
                .build());

        log.info("验证码邮件已发送：email={} scene={} 内联图片={}",
                normalizedEmail, scene, content.inlineImages().size());
    }

    /**
     * 纯文本正文。
     *
     * <p>HTML 版由 {@code EmailCodeTemplate} 渲染，这里这一份有两个作用：
     * 一是 {@code multipart/alternative} 的降级分支（只认纯文本的客户端、
     * 或被反垃圾系统降级呈现时），二是"万一 HTML 被判成垃圾特征"时的兜底 ——
     * 两种情况下列表里都**必须**有那 6 位数字，因此这份纯文本不是可以随手删掉的重复。
     *
     * <p>刻意带上有效期与"非本人操作请忽略"的说明：用户收到一封没有上下文的验证码邮件时，
     * 最可能的行为是怀疑是钓鱼邮件并举报它 —— 而举报会直接损伤发信域名的信誉。
     * 一段说明文字的成本极低，收益是把"被举报"变成"被忽略"。
     */
    private String buildTextBody(int minutes, EmailScene scene, String code) {
        return "您的" + scene.label() + "验证码是：" + code + "\n\n"
                + "有效期 " + minutes + " 分钟，请勿转发给任何人。\n"
                + "如果不是您本人操作，请忽略本邮件。\n";
    }
}
