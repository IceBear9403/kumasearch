package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 一次滑块挑战的完整数据，由 {@code GET /api/captcha/get} 返回。
 *
 * <p>字段与 AJ-Captcha 的 {@code CaptchaVO} 一一对应，但<b>刻意不复用它</b>：
 * {@code CaptchaVO} 同时承载了请求与响应两种用途（它既是取图的入参，
 * 又是校验的入参和出参），字段里混着 {@code pointJson}、{@code result}、
 * {@code browserInfo} 这些与响应无关的东西。直接把它当响应体返回，
 * 会让前端拿到一堆它不该看到、也不该依赖的字段 ——
 * 尤其是 {@code point}（正确坐标），那等于把答案发给客户端。
 *
 * <p>因此这里只声明前端真正要用的四项。返回体是<b>契约</b>，
 * 而 {@code CaptchaVO} 是上游的内部结构，两者的变化节奏不同。
 */
@Data
@Builder
@Schema(description = "滑块挑战数据，含背景图、拼图块与本次会话 ID")
public class CaptchaChallengeResponse {

    /**
     * 本次挑战的会话 ID，前端在提交时原样带回。
     *
     * <p>对应上游的 {@code token}。它<b>不是</b>凭证：它只标识"这一次取图"，
     * 服务端凭它找回这份挑战对应的正确坐标。没有任何身份含义，
     * 因此可以放心发给匿名用户。
     */
    @Schema(description = "本次挑战的会话 ID，校验滑块时原样带回", example = "a3f1c9e2b7d44e8f9a0b1c2d3e4f5061")
    private String captchaId;

    /** 验证码类型，固定 {@code blockPuzzle}。前端据此决定渲染成哪种交互。 */
    @Schema(description = "验证码类型，固定 blockPuzzle", example = "blockPuzzle")
    private String captchaType;

    /** 背景图，Base64 编码的 JPEG，<b>不带</b> {@code data:image/jpeg;base64,} 前缀。 */
    @Schema(description = "背景图，Base64 编码的 JPEG（不含 data: 前缀）", example = "/9j/4AAQSkZJRgABAQAAAQ...")
    private String originalImageBase64;

    /** 滑块拼图块，Base64 编码的 PNG（带透明通道），同样不带前缀。 */
    @Schema(description = "滑块拼图块，Base64 编码的 PNG（不含 data: 前缀）", example = "iVBORw0KGgoAAAANSUhEUg...")
    private String jigsawImageBase64;

    /**
     * 坐标加密用的密钥。
     *
     * <p>只在 {@code kuma.captcha.aes-status=true} 时非空。
     *
     * <p>⚠️ <b>它随响应一起下发给前端，因此不构成任何安全能力</b> ——
     * 前端不拿到它就无法加密坐标，所以它必然对客户端可见，也就是说任何人
     * 都能用它构造一份"看起来正规"的请求。真正的判定仍然是服务端拿坐标
     * 与缺口位置比对，跟这个密钥无关。见 {@code KumaSearchProperties.Captcha#aesStatus}。
     */
    @Schema(description = "坐标加密密钥，仅 kuma.captcha.aes-status=true 时非空", example = "0123456789abcdef")
    private String secretKey;
}
