package com.kuma.search.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 滑块校验请求体，对应 {@code POST /api/captcha/check}。
 *
 * <h3>方向是固定的：进的是轨迹、出的是凭证</h3>
 * 这个接口只做一级校验 —— 前端把 {@code captchaId} 与拖动轨迹交上来，
 * 服务端比对缺口位置，通过则<b>签发</b>一枚一次性凭证（见 {@code CaptchaCheckResponse}）。
 * 校验并<b>消费</b>那枚凭证是业务接口（注册 / 登录 / 发码）的事。
 *
 * <h3>字段曾经有两个候选，现在只剩一个</h3>
 * AJ-Captcha 的 {@code CaptchaVO} 同时服务于取图与校验，因此它既接受
 * {@code token + pointJson}（会话 ID + 轨迹），也接受 {@code captchaVerification}
 * （整体凭证）。本项目采用的是前者：上游 {@code CaptchaService#check} 读的正是
 * {@code token}/{@code pointJson}，{@code captchaVerification} 是它的<b>输出</b>字段，
 * 由 {@code verification} 方法消费。所以本 DTO 里那个与响应同名的
 * {@code captchaVerification} 字段是多余的，已删除 ——
 * 留着它会让"到底该传哪个"这个问题在每次读代码时重新出现一次。
 *
 * <h3>两个字段都设了长度上限</h3>
 * 它们的值会被送进 AJ-Captcha 的缓存查询，也是拼 Redis key 的原料
 * （见 {@code AjCaptchaVerifier#consumeOnce}）。不设上限的话，
 * 一个超长字符串就是一个能反复制造大 key 的写入手段。
 */
@Data
@Schema(description = "滑块校验请求体")
public class CaptchaCheckRequest {

    /**
     * 本次滑块的会话 ID，对应上游的 {@code token}，由 {@code GET /api/captcha/get} 返回。
     *
     * <p>服务端靠它找回这道题的正确坐标。<b>缺失直接拒绝</b>，
     * 不做"没有会话就在缓存里把所有题目试一遍"这类兜底。
     */
    @Size(max = 128, message = "captchaId 长度不能超过 128")
    @Schema(description = "本次滑块的会话 ID，由取滑块图接口返回", example = "a3f1c9e2b7d44e8f9a0b1c2d3e4f5061")
    private String captchaId;

    /**
     * 滑块轨迹点，由前端采集（JSON 数组，形如 {@code [{"x":137,"y":5}]}）。
     *
     * <p>不在这里解析成结构化对象：格式由 AJ-Captcha 定义，本项目做二次解析
     * 只会多一处与上游版本耦合的代码 —— 我们只是把它转交给上游的 {@code check}。
     */
    @Size(max = 4096, message = "trackList 长度不能超过 4096")
    @Schema(description = "滑块拖动轨迹点，JSON 数组字符串（由前端采集）", example = "[{\"x\":137,\"y\":5},{\"x\":150,\"y\":6}]")
    private String trackList;
}
