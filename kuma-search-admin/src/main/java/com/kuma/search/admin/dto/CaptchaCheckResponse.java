package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 滑块校验响应体，对应 {@code POST /api/captcha/check} 的 {@code data} 字段。
 *
 * <h3>为什么返回的是一个对象而不是一个裸字符串</h3>
 * 这个接口的作用是「把滑块的轨迹换成一张可以递给业务接口的凭证」，
 * 前端拿到之后必须原样填进注册/登录/发码请求的 {@code captchaVerification} 字段。
 * 用 {@code R<String>} 返回的话，这个字符串的含义只能靠调用方去猜 ——
 * 它可能被误当成会话 ID、当成"校验通过"的提示语，或者被丢掉。
 * 单独一个带名字的字段让"它是什么、要拿去干什么"在类型上就写清楚了，
 * 将来上游的响应里多出别的字段（如滑块二次校验模式下的 {@code captchaId}）也有地方放。
 *
 * <p>字段只有 {@code captchaVerification} 一个：这里刻意<b>不</b>回显
 * {@code captchaId}、也不回显轨迹校验是否命中的细节 ——
 * 那些信息对前端没用，而"多送一个字段"这种习惯会在下一次加字段时变成真问题。
 */
@Data
@Builder
@Schema(description = "滑块校验响应，返回一次性凭证")
public class CaptchaCheckResponse {

    /**
     * 一次性凭证。
     *
     * <p>⚠️ <b>只能用一次</b>，且用它的动作发生在<b>业务接口</b>而非本接口：
     * 校验通过并消费掉它的是 {@code CaptchaVerifier#verify}，
     * 由注册/登录/发码那三处调用。因此前端拿到它之后不能重复提交同一个表单 ——
     * 第二次提交会被判定为「凭证已使用」。
     */
    @Schema(description = "一次性凭证，须交给后续业务接口（注册 / 登录 / 发码）校验",
            example = "7f3a9c1e5b2d4a6f0e8c1b3d5f7a9c2e")
    private String captchaVerification;
}
