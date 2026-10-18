package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送邮箱验证码请求体，对应 {@code POST /api/auth/email-code}。
 *
 * <p>校验顺序由 {@code EmailCodeService} 保证，其中<b>滑块必须排在最前</b>：
 * 这个接口的每一次成功调用都要真金白银地发一封邮件，
 * 若不先过滑块，邮件额度就是一个谁都能刷的免费攻击面。
 */
@Data
@Schema(description = "发送邮箱验证码请求体")
public class EmailCodeRequest {

    /**
     * 收件邮箱。长度上限与 {@code t_sys_user.email} 的列宽一致（RFC 5321 的地址上限是 254）。
     *
     * <p>落库与查库前都会被 {@code EmailNormalizer} 归一化（trim + 小写），
     * 因此这里<b>不做大小写约束</b>：让用户输入 {@code Foo@x.com} 是合理的，
     * 拒绝它只会制造"我明明填对了"的困惑。
     */
    @NotBlank(message = "email 不能为空")
    @Email(message = "email 格式不正确")
    @Size(max = 254, message = "email 长度不能超过 254")
    @Schema(description = "收件邮箱", example = "user@example.com")
    private String email;

    /**
     * 场景，取值为 {@code REGISTER} / {@code LOGIN}。
     *
     * <p>用字符串而不是枚举接收：未知取值要在 {@code EmailScene.parse} 里
     * 报一个带业务语义的参数错误，而 Jackson 反序列化失败只会得到一条
     * 「JSON parse error」的框架级报错 —— 前者能直接告诉前端该改什么。
     */
    @NotBlank(message = "scene 不能为空")
    @Size(max = 16, message = "scene 长度不能超过 16")
    @Schema(description = "使用场景，取值 REGISTER / LOGIN", example = "REGISTER")
    private String scene;

    /**
     * 滑块校验凭证（AJ-Captcha 的 {@code captchaVerification}）。
     *
     * <p><b>一次性凭证</b>：校验成功后立即作废，同一份凭证不能重放第二次。
     * 容量上限 512 是防范「用一个超长字符串把 Redis key 撑大」——
     * 该凭证会被拼进 Redis key 或用它去查缓存。
     */
    @NotBlank(message = "captchaVerification 不能为空")
    @Size(max = 512, message = "captchaVerification 长度不能超过 512")
    @Schema(description = "滑块校验凭证（一次性）", example = "7f3a9c1e5b2d4a6f0e8c1b3d5f7a9c2e")
    private String captchaVerification;
}
