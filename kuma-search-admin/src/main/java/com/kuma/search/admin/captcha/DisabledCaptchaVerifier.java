package com.kuma.search.admin.captcha;

import com.kuma.search.admin.dto.CaptchaChallengeResponse;
import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code kuma.captcha.enabled=false} 时的 {@link CaptchaVerifier} 实现：<b>一律拒绝</b>。
 *
 * <p>这个类什么都不做，却必须存在 —— 它的存在本身就是设计决策：
 * 「验证码没开」与「验证码校验通过」是两件完全不同的事，
 * 而一个返回 {@code void} 的接口最容易被写成"开关关掉就 return"
 * （方法体是空的，看起来无懈可击）。这样写的结果是：滑块功能一旦因为
 * 配置遗漏或依赖缺失而未生效，注册、登录、发码三条链路会静默地全部失去这道防线，
 * 且没有任何地方会报错。
 *
 * <p>因此这里明确地抛异常。它会让「验证码没打开」这件事以用户可见的方式暴露出来，
 * 从而在部署当天就被解决 —— 而不是在几个月后的一次安全复查里被发现。
 */
@Component
@ConditionalOnProperty(prefix = "kuma.captcha", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledCaptchaVerifier implements CaptchaVerifier {

    /**
     * 未开通时出题也拒绝。
     *
     * <p>返回一个空挑战对象看起来更"友好"，但那会让前端渲染出一个
     * 拖动后永远无法通过的空滑块 —— 用户会去反复重试，而真正的问题是
     * 服务端根本没开这个功能。明确拒绝才能让"未开通"被立刻看见。
     */
    @Override
    public CaptchaChallengeResponse createChallenge() {
        throw BizException.of(ResultCode.SYSTEM_ERROR,
                "人机校验未开通（kuma.captcha.enabled=false），注册与登录功能暂不可用");
    }

    /**
     * 未开通时一级校验也拒绝：滑块根本发不出来，自然没有轨迹可校验。
     *
     * <p>取图接口（{@code GET /api/captcha/get}）在调用本方法之前就据 {@link #isEnabled()}
     * 报过「未开通」了，因此正常路径走不到这里。保留它并同样抛异常，是为了让
     * 「这个实现不会放行任何人」在类型层面成立 —— 少实现一个方法就编不过，
     * 而不是留一个将来可能被填成 {@code return null} 的缺口。
     */
    @Override
    public String challenge(String captchaId, String trackList) {
        throw BizException.of(ResultCode.SYSTEM_ERROR,
                "人机校验未开通（kuma.captcha.enabled=false），注册与登录功能暂不可用");
    }

    @Override
    public void verify(String captchaVerification) {
        throw BizException.of(ResultCode.SYSTEM_ERROR,
                "人机校验未开通（kuma.captcha.enabled=false），注册与登录功能暂不可用");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
