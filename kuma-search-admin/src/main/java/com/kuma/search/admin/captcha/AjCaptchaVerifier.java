package com.kuma.search.admin.captcha;

import com.anji.captcha.model.common.CaptchaTypeEnum;
import com.anji.captcha.model.common.RepCodeEnum;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import com.kuma.search.admin.dto.CaptchaChallengeResponse;
import com.kuma.search.admin.security.AuthKeys;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * AJ-Captcha 滑块验证码的校验实现（{@code kuma.captcha.enabled=true} 时装配）。
 *
 * <h3>两段校验各自映射到上游的哪个方法</h3>
 * 上游 {@code CaptchaService} 的三个方法与本项目的方法是对应的，但<b>不是一对一</b>，
 * 这一点如果不先讲清楚，读代码时很容易以为少调了什么：
 *
 * <table border="1">
 *   <caption>方法对应关系</caption>
 *   <tr><th>本项目</th><th>上游</th><th>做什么</th></tr>
 *   <tr><td>{@link #createChallenge}</td><td>{@code get}</td>
 *       <td>出题。把正确坐标写进缓存，只把图与 token 发出去</td></tr>
 *   <tr><td>{@link #challenge}</td><td>{@code check}</td>
 *       <td>比对坐标，通过则签发 {@code captchaVerification}</td></tr>
 *   <tr><td>{@link #verify}</td><td>{@code verification}</td>
 *       <td>校验凭证，并消费它</td></tr>
 * </table>
 *
 * <p>也就是说上游的 {@code get} 不是"获取凭证"而是"出一道题"，
 * 它<b>不</b>对应本项目任何一个业务侧方法 —— 它对应的是前端先调的那个取图接口。
 *
 * <h3>⚠️ 那枚凭证是上游留给前端算的，本项目改由服务端算</h3>
 * 上游 {@code check} 成功时把它写进缓存、<b>却没有放回响应对象</b>
 * （{@code CaptchaVO#captchaVerification} 在核心包里从无赋值）。
 * 官方的接法是前端拿 {@code secretKey} 自己拼 / 加密这枚串，
 * 本项目不用 starter、也就没有那层前端 SDK，因此改由
 * {@link #buildVerification} 在服务端补上。这不改变安全性质：
 * 这枚串只是缓存里那个键的名字，<b>能不能过是"缓存里有没有"决定的</b>，
 * 客户端即使知道拼接规则也伪造不出一个存在过的键（{@code check} 成功后才会写入）。
 *
 * <h3>⚠️ 上游的凭证消费是有竞态的，因此本类仍然自己消费一次</h3>
 * 上游 {@code verification} 的实现是「{@code exists} 判断 → {@code delete}」两步。
 * 两个并发的相同请求可以双双通过 {@code exists}，然后其中一个的 {@code delete}
 * 才生效 —— 这正是重放攻击会制造的形态。<b>能通过一次的凭证，理论上能被并发通过两次。</b>
 * 因此 {@link #consumeOnce} 用 {@code SET NX} 再做一次原子消费：
 * 它才是"同一枚凭证只放行一次"的<b>唯一</b>无竞态保证。
 *
 * <p>这不是不理解上游而重复劳动 —— 上游那一步仍然必须调（它做的验签是
 * 我们做不到的），两层是叠加关系：上游负责"这枚凭证是不是我签发的"，
 * 本类负责"这枚凭证有没有被用过一次"。
 *
 * <h3>上游的错误码要翻译，不能直接透传</h3>
 * 上游返回的 {@code repCode} 是它自己的编码体系（{@code 6110}、{@code 6111}…），
 * 与本项目的 {@code ResultCode} 无关。直接把它塞进响应会让前端看到
 * 一个别处从未出现过的码，而前端的错误处理是按本项目编码写的。
 * 因此 {@link #translate} 逐个映射，并且<b>映射不是"全部当成失败"</b> ——
 * 上游的限流码要映射成 {@code RATE_LIMITED}，让前端的"稍后重试"逻辑能生效；
 * 底图没加载成功这类是服务端故障，映射成 {@code SYSTEM_ERROR} 而不是
 * "验证失败"，否则用户会一直重试一个与他无关的问题。
 *
 * <h3>已实现、且与上游版本无关的部分</h3>
 * 「一次性消费」的完整逻辑见 {@link #consumeOnce}，它在依赖接入前就已写好，
 * 且不依赖上游任何 API。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kuma.captcha", name = "enabled", havingValue = "true")
public class AjCaptchaVerifier implements CaptchaVerifier {

    /** 只有滑块拼图，理由见 {@code KumaSearchProperties.Captcha#type}。 */
    private static final String TYPE = CaptchaTypeEnum.BLOCKPUZZLE.getCodeValue();

    /**
     * 凭证里 token 与坐标之间的分隔符。
     *
     * <p>取值由上游决定，<b>不能改</b>：它就是上游写缓存时用的那个字面量（见 {@link #buildVerification}）。
     */
    private static final String VERIFICATION_SEPARATOR = "---";

    private final CaptchaService captchaService;
    private final StringRedisTemplate stringRedisTemplate;
    private final KumaSearchProperties properties;

    public AjCaptchaVerifier(CaptchaService captchaService,
                             StringRedisTemplate stringRedisTemplate,
                             KumaSearchProperties properties) {
        this.captchaService = captchaService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    @Override
    public CaptchaChallengeResponse createChallenge() {
        CaptchaVO request = new CaptchaVO();
        request.setCaptchaType(TYPE);

        ResponseModel response = captchaService.get(request);
        if (response == null) {
            // 上游在 limitHandler 为 null 的路径上会返回 null —— 正常情况下不会走到，
            // 因为它拿到图之后必然返回一个带 repData 的成功响应。真出现说明上游行为变了，
            // 必须报出来而不是当成"没有验证码"放过去。
            throw unexpected("取图返回空响应");
        }
        if (isFailure(response)) {
            throw translate(response, "生成滑块失败");
        }

        Object data = response.getRepData();
        if (!(data instanceof CaptchaVO vo)) {
            throw unexpected("取图响应的 repData 不是 CaptchaVO，实际为 "
                    + (data == null ? "null" : data.getClass().getName()));
        }

        return CaptchaChallengeResponse.builder()
                .captchaId(vo.getToken())
                .captchaType(TYPE)
                .originalImageBase64(vo.getOriginalImageBase64())
                .jigsawImageBase64(vo.getJigsawImageBase64())
                // aes-status=false 时上游返回 null，此时前端直接发明文坐标 JSON，
                // 两边都不加密（见 KumaSearchProperties.Captcha#aesStatus 的说明）
                .secretKey(vo.getSecretKey())
                .build();
    }

    @Override
    public String challenge(String captchaId, String trackList) {
        // 滑块拼图这两个都是必需的：captchaId 用来找回正确坐标，trackList 是用户给的答案。
        // 少了任何一个都无法判定，而"少一个就以另一个为准"这种宽容只剩误导。
        if (!StringUtils.hasText(captchaId)) {
            throw BizException.of(ResultCode.CAPTCHA_INVALID, "缺少滑块会话标识，请重新获取验证码");
        }
        if (!StringUtils.hasText(trackList)) {
            throw BizException.of(ResultCode.CAPTCHA_INVALID, "缺少滑块位置，请重新拖动一次");
        }

        CaptchaVO request = new CaptchaVO();
        request.setCaptchaType(TYPE);
        request.setToken(captchaId);
        request.setPointJson(trackList);

        ResponseModel response = captchaService.check(request);
        if (response == null) {
            throw unexpected("轨迹校验返回空响应");
        }
        if (isFailure(response)) {
            throw translate(response, "滑块校验未通过，请重新拖动");
        }

        Object data = response.getRepData();
        String fromUpstream = data instanceof CaptchaVO vo ? vo.getCaptchaVerification() : null;
        if (StringUtils.hasText(fromUpstream)) {
            return fromUpstream;
        }

        // 走到这里不是异常，是**上游的正常行为**：核心包的 check 成功时并不把
        // captchaVerification 放回 VO，它是留给前端自己算的（见 buildVerification）。
        // 我们不用 starter、也就没有那层前端 SDK，所以这一枚由服务端补上 ——
        // 由服务端算还顺带消除了"前后端拼接出的字符串差一个字节"这个失败模式，
        // 而那正是这类凭证最容易出的问题（字符串是它唯一的身份，差一个字符就是另一种凭证）。
        //
        // 这里【不消费】凭证：它此刻才刚被签发，要被业务接口消费一次。见 CaptchaVerifier#challenge。
        return buildVerification(captchaId, trackList);
    }

    /**
     * 按上游的规则拼出 {@code captchaVerification}。
     *
     * <h3>为什么是这个形式</h3>
     * 上游 {@code check} 在坐标比对通过后做了两件事：把
     * {@code AESUtil.aesEncrypt(token + "---" + pointJson, secretKey)} 写进缓存
     * （键 {@code RUNNING:CAPTCHA:second-{该串}}，值 {@code token}，TTL 180 秒），
     * 并把同一枚串当作"凭证"交给调用方。而 {@code verification} 那一侧做的判断只有一句：
     * 客户端带来的这枚串在缓存里存在吗。也就是说<b>这枚串是它自己的主键</b>，
     * 内容不是给人读的，但必须与上游写进缓存的完全一致。
     *
     * <p>{@code secretKey} 在 {@code kuma.captcha.aes-status=false} 时为空，
     * 而上游的 {@code AESUtil} 对空密钥是<b>原样返回</b>（不是加密出空串、也不抛异常），
     * 于是那枚串就是明文拼接。这也是为什么本类可以自己算：
     * 加密模式下需要前端参与，那种模式已被 {@code AjCaptchaConfig} 的启动自检禁用。
     *
     * <p>{@code pointJson} 必须是<b>原样</b>的轨迹字符串。上游拼进去的是它自己解密后的结果，
     * 在明文模式下解密是恒等变换 —— 但上游那个手写的 {@code PointVO.parse}
     * 只按 {@code ,} 与 {@code :} 切分，不做归一化，所以哪怕只多一个空格，
     * 服务端写进缓存的键与它交给前端的串就会是两枚不同的凭证。
     * 这里直接用调用方传来的 {@code trackList}，不重新序列化、不 trim，同样是为了不多一个字节。
     */
    private String buildVerification(String token, String pointJson) {
        return token + VERIFICATION_SEPARATOR + pointJson;
    }

    @Override
    public void verify(String captchaVerification) {
        if (!StringUtils.hasText(captchaVerification)) {
            throw BizException.of(ResultCode.CAPTCHA_INVALID, "缺少人机校验凭证，请重新完成滑块校验");
        }

        CaptchaVO request = new CaptchaVO();
        request.setCaptchaType(TYPE);
        request.setCaptchaVerification(captchaVerification);

        ResponseModel response = captchaService.verification(request);
        if (response == null) {
            throw unexpected("凭证校验返回空响应");
        }
        if (isFailure(response)) {
            throw translate(response, "人机校验未通过，请重新完成滑块校验");
        }

        // 校验通过之后立刻消费凭证，两步不可颠倒、也不可拆给调用方
        consumeOnce(captchaVerification);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /** 上游以 {@code repCode == "0000"} 表示成功。 */
    private boolean isFailure(ResponseModel response) {
        return !RepCodeEnum.SUCCESS.getCode().equals(response.getRepCode());
    }

    /**
     * 把上游的错误码翻译成本项目的 {@code ResultCode}。
     *
     * <p>映射原则：<b>用户能靠重试解决的问题</b>（滑歪了、挑战过期、被限流）
     * 与<b>用户重试也没用的问题</b>（底图没加载出来）必须分开 ——
     * 前者给 {@code CAPTCHA_INVALID}（前端会提示"重新拖一次"），
     * 后者给 {@code SYSTEM_ERROR}（前端会说"暂时不可用"，而不是让用户
     * 反复重试一个服务端故障）。
     *
     * @param fallbackMessage 兜底文案，用于上游没给出可读 repMsg 的情况
     */
    private BizException translate(ResponseModel response, String fallbackMessage) {
        String repCode = response.getRepCode();
        String repMsg = StringUtils.hasText(response.getRepMsg())
                ? response.getRepMsg() : fallbackMessage;

        if (repCode == null) {
            return unexpected("上游返回了空错误码");
        }

        return switch (repCode) {
            // 6110 验证码已失效（挑战过期或 token 对不上）、6111 坐标不匹配、
            // 6206 无效请求 —— 这三类是"再来一次就可能成功"，交回给用户
            case "6110", "6111", "6206" -> {
                log.debug("滑块一级校验未通过: repCode={} repMsg={}", repCode, repMsg);
                yield BizException.of(ResultCode.CAPTCHA_INVALID, repMsg);
            }
            // 上游自带的频次限制（只在 kuma.captcha.req-frequency-limit-enable=true 时才会触发）
            case "6201", "6202", "6204", "6205" -> {
                log.warn("滑块接口被上游频次限制拦截: repCode={} repMsg={}", repCode, repMsg);
                yield BizException.of(ResultCode.RATE_LIMITED, "操作过于频繁，请稍后再试");
            }
            // 6112 生成失败、6113 底图未初始化 —— 服务端问题，用户重试无用
            case "6112", "6113" -> {
                log.error("滑块服务端故障: repCode={} repMsg={}，"
                        + "6113 通常意味着 defaultImages 未能从 jar 内加载", repCode, repMsg);
                yield BizException.of(ResultCode.SYSTEM_ERROR, "人机校验暂时不可用，请稍后重试");
            }
            default -> {
                log.error("滑块返回了未映射的错误码: repCode={} repMsg={}", repCode, repMsg);
                yield BizException.of(ResultCode.SYSTEM_ERROR, "人机校验暂时不可用，请稍后重试");
            }
        };
    }

    /**
     * 上游行为不符合预期时的统一出口。
     *
     * <p>这些分支理论上不会走到。把它们单独记成 error 而不是混进 {@code translate}：
     * 它们代表"上游的契约变了"，与本项目配置错误是两回事，
     * 日志里分开才能一眼看出该去升级依赖还是改配置。
     */
    private BizException unexpected(String what) {
        log.error("滑块验证码的上游行为异常：{}。这通常意味着 captcha 依赖升级后契约发生了变化，"
                + "请对照 AjCaptchaVerifier 的类注释核对其 API。", what);
        return BizException.of(ResultCode.SYSTEM_ERROR, "人机校验暂时不可用，请稍后重试");
    }

    /**
     * 消费凭证：同一个 {@code captchaVerification} 只允许通过一次。
     *
     * <p>用 {@code SET NX} 而不是「先 GET 判断、再 SET 写入」：
     * 后者的两个并发请求会双双读到"未使用"、双双放行，而重放攻击本来就是并发行为。
     * 这也正是本类没有把"消费"这件事完全交给上游的原因，见类注释。
     *
     * <p><b>Redis 异常时拒绝服务（fail-closed）</b>，与 {@code LoginAttemptGuard} 同向、
     * 与 {@code RateLimitGuard} 反向。判断依据是「这次失败造成的是安全问题还是可用性问题」：
     * 无法确认凭证是否已被用过，就等于无法排除重放，这是一个安全判断而非可用性判断；
     * 而限流失效只是"ES 短时被打慢"，属于可用性问题。
     *
     * <p>TTL 取 {@code kuma.captcha.verification-ttl-seconds}：这个值不是"凭证有效期"
     * （那由前端发起滑块的时刻决定），而是"凭证已被消费"这个事实需要被记住多久 ——
     * 它必须不短于凭证本身可能被重放的时间窗，取 120 秒对应滑块结果的自然时效。
     */
    private void consumeOnce(String captchaVerification) {
        int ttlSeconds = properties.getCaptcha().getVerificationTtlSeconds();
        if (ttlSeconds <= 0) {
            // 配 0 等于关掉防重放。不静默跳过，而是明确拒绝：这与整个类"不假装可用"的取向一致。
            log.error("kuma.captcha.verification-ttl-seconds={} 非法，滑块凭证无法防重放，拒绝服务", ttlSeconds);
            throw BizException.of(ResultCode.SYSTEM_ERROR, "人机校验配置有误，请联系管理员");
        }

        String key = AuthKeys.captchaUsed(hash(captchaVerification));
        try {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
            if (!Boolean.TRUE.equals(first)) {
                // 走到这里有两种可能：真的在重放，或者用户重复提交了同一个表单。
                // 两种都不该放行 —— 后者会让他看到一次明确提示，而前者的入口就此关闭。
                log.warn("滑块凭证已被使用过，拒绝本次请求（可能是重放或重复提交）");
                throw BizException.of(ResultCode.CAPTCHA_INVALID, "人机校验凭证已使用，请重新完成滑块校验");
            }
        } catch (BizException e) {
            // 上面那个"已使用"的异常不能被下面的 catch 吞掉并改成 Redis 故障文案
            throw e;
        } catch (RuntimeException e) {
            log.error("滑块凭证防重放校验失败（Redis 异常），按 fail-closed 拒绝: {}", e.getMessage());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "人机校验暂时不可用，请稍后重试");
        }
    }

    /**
     * 凭证的散列。
     *
     * <p>用 {@link UUID#nameUUIDFromBytes} 而不是手写 SHA-256：它基于 MD5，
     * 输出固定 32 位十六进制、无需处理受检异常，而这里的用途只是"把一个几百字符的
     * 凭证压成一个可作 key 的短串"，密码学强度无关紧要 ——
     * 攻击者无法通过构造碰撞来重放，因为碰撞的另一端是<b>他自己没见过的</b>凭证。
     */
    private String hash(String verification) {
        return UUID.nameUUIDFromBytes(verification.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }
}
