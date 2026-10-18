import { get, post } from './request'

import type {
  CaptchaChallenge,
  CaptchaCheckRequest,
  CaptchaVerificationResult,
} from '@/types/captcha'

/**
 * 取滑块图。**匿名可用**。
 *
 * ⚠️ `kuma.captcha.enabled=false` 时后端<b>拒绝服务</b>而不是跳过校验，
 * 因为「关掉开关就不校验」会让配置遗漏变成一次静默的防线消失
 * （见后端 `CaptchaVerifier` 的类注释）。调用方要把这类失败当作
 * 「注册与登录暂时不可用」处理，而**不是**当作「这次网络不好」重试 ——
 * 重试一百次结果一样。`SliderCaptcha` 组件是这段判断的落点。
 *
 * 路径是 `/api/captcha/**` 下的字面量，不是配置项：AJ-Captcha 的核心包
 * （`com.anji-plus:captcha`）不含任何控制器，这个端点由后端
 * `CaptchaController` 自己提供，因此与 `SecurityConfig` 里放行的
 * `/api/captcha/**` 天然绑在一起，不存在"两处配置不一致"的可能。
 * （早期文档里说的 `kuma.captcha.get-url` 是把 starter 的默认路径改过来的做法，
 * 实为臆测，那个配置项在上游根本不存在，已删除。）
 */
export function getCaptcha(): Promise<CaptchaChallenge> {
  return get<CaptchaChallenge>('/captcha/get')
}

/**
 * 校验滑块轨迹，换回一次性凭证。**匿名可用**。
 *
 * ⚠️ 这个接口做的是「一级校验」：**进的是轨迹、出的是凭证**，
 * 它**不消费**任何东西。凭证的校验与消费发生在业务接口那一侧。
 * 因此不能把这里成功当作「人机校验已完成」直接提交表单 ——
 * 必须把返回的 `captchaVerification` 交给 `register` / `login` / `sendEmailCode`。
 */
export function checkCaptcha(data: CaptchaCheckRequest): Promise<CaptchaVerificationResult> {
  return post<CaptchaVerificationResult>('/captcha/check', data)
}
