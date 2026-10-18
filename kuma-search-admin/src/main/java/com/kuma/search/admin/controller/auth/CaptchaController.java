package com.kuma.search.admin.controller.auth;

import com.kuma.search.admin.captcha.CaptchaVerifier;
import com.kuma.search.admin.dto.CaptchaChallengeResponse;
import com.kuma.search.admin.dto.CaptchaCheckRequest;
import com.kuma.search.admin.dto.CaptchaCheckResponse;
import com.kuma.search.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图形验证码（滑块）接口。
 *
 * <h3>路径为什么是 {@code /api/captcha/**}，以及为什么这里可以直接写死</h3>
 * 曾经担心过"上游 starter 自带控制器、默认挂 {@code /captcha/get}，
 * 需要用配置把路径改到 {@code /api/**} 下"，因为那两个默认路径会同时踩两个坑：
 * 不在 {@code /api/**} 下会被兜底鉴权拦成 401（用户会陷入"要登录才能拿到登录用的验证码"）；
 * 而前端走 {@code VITE_API_BASE_URL=/kumasearch/api}、Nginx 只代理 {@code /api/**}，
 * 请求根本到不了后端。
 *
 * <p>实测后这个担心不成立：<b>上游核心包不含任何控制器</b>（也不含任何
 * javax/jakarta.servlet 引用），端点本来就由本类提供。因此路径是
 * {@code @RequestMapping} 上的字面量，<b>不是配置项</b> ——
 * {@code SecurityConfig} 里放行的 {@code /api/captcha/**} 与本类绑定在一起，
 * 不存在"两处配置不一致"的可能。
 *
 * <h3>响应体统一包一层 {@code R}</h3>
 * 上游的响应结构是它自己的 {@code {repCode, repMsg, repData}}，与本项目其它接口不同。
 * {@link CaptchaVerifier} 已经把上游那几个方法的结果翻译成了本项目的
 * {@code ResultCode} 与本类的 {@code R<>}，因此前端只需要适配一种结构，
 * 网关/监控也能按同一条规则判断"这次调用成功了吗"。
 * 这个翻译是有成本的（见 {@code AjCaptchaVerifier#translate}），
 * 但它把上游的编码体系挡在了一个类里，而不是渗透到前端。
 */
@Slf4j
@RestController
@RequestMapping("/api/captcha")
@Tag(name = "图形验证码", description = "滑块验证码的取图与校验，全部匿名可用")
public class CaptchaController {

    private final CaptchaVerifier captchaVerifier;

    public CaptchaController(CaptchaVerifier captchaVerifier) {
        this.captchaVerifier = captchaVerifier;
    }

    /**
     * 取滑块图。匿名可访问。
     *
     * <p>返回背景图、拼图块与本次会话 ID。正确坐标留在服务端（Redis），
     * <b>不随响应下发</b> —— 那等于把答案发给客户端。
     *
     * <p>未开通时这里会抛「未开通」，而不是返回一个空挑战对象：
     * 空对象会让前端渲染出一个拖动后永远无法通过的空滑块，
     * 用户会去反复重试，而真正的问题是服务端根本没开这个功能。
     */
    @GetMapping("/get")
    @Operation(summary = "取滑块图",
            description = """
                    匿名可访问。返回背景图、拼图块与本次会话 ID（captchaId）。
                    功能未开通时会直接报错，而不是返回一个空挑战。""")
    public R<CaptchaChallengeResponse> get() {
        return R.ok(captchaVerifier.createChallenge());
    }

    /**
     * 校验滑块轨迹，签发一次性凭证。匿名可访问。
     *
     * <h3>⚠️ 这个接口做的是「一级校验」，不是「校验凭证」</h3>
     * 它的输入是<b>轨迹</b>（{@code captchaId} + {@code trackList}），输出是<b>凭证</b>。
     * 凭证的校验与消费发生在业务接口那一侧（见 {@link CaptchaVerifier#verify}），
     * 也就是注册/登录/发码。两段的分工不能合并，原因写在
     * {@link CaptchaVerifier#challenge} 的注释里 —— 简言之：
     * 合并之后这个接口会把凭证消费掉，而紧随其后的注册请求拿同一枚凭证来校验，
     * 会被判成「已使用」，注册就永远不可能成功。
     *
     * <p>因此前端必须把返回值里的 {@code captchaVerification} 交给后续的业务请求，
     * 而不能把本接口的「返回成功」当作"校验已完成"直接提交表单。
     */
    @PostMapping("/check")
    @Operation(summary = "校验滑块",
            description = """
                    匿名可访问。返回的 captchaVerification 是一次性凭证，须交给后续业务接口（注册 / 登录 / 发码）消费。
                    本接口返回成功不代表凭证已消费，不要据此直接提交业务表单。""")
    public R<CaptchaCheckResponse> check(@Valid @RequestBody CaptchaCheckRequest request) {
        String verification = captchaVerifier.challenge(
                request.getCaptchaId(), request.getTrackList());
        return R.ok(CaptchaCheckResponse.builder().captchaVerification(verification).build());
    }
}
