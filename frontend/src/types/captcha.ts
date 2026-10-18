/**
 * 人机校验（滑块验证码）相关类型。
 *
 * # 这里的字段是**服务端的契约**，不是上游 SDK 的字段并集
 *
 * 三个类型分别对应后端 `CaptchaChallengeResponse`（取图）、
 * `CaptchaCheckRequest`（校验轨迹）、`CaptchaCheckResponse`（换回的凭证）。
 * 后端的 `CaptchaVerifier` 已经把 AJ-Captcha 的 `{repCode, repMsg, repData}`
 * 翻译成了本项目的 `R<T>`，所以前端只需要认识下面这几项。
 *
 * **字段一律是必填的**，只有明确会缺的才标可选 ——
 * 一个全是可选字段的接口，把字段名拼错时不会有任何提示。
 */

/**
 * 一次滑块挑战，`GET /api/captcha/get` 的返回。
 *
 * ⚠️ **正确坐标不在这里，也不应该在这里。** 它留在服务端的 Redis 里，
 * 由 `/api/captcha/check` 拿去比对。任何"照着响应自己算出滑到哪"的做法
 * 都等于把答案写在客户端，滑块也就只剩装饰作用。
 */
export interface CaptchaChallenge {
  /** 本次挑战的会话 ID，校验时必须原样带回 */
  captchaId: string
  /** 滑块类型。目前后端只启用 `blockPuzzle` */
  captchaType: string
  /** 背景图，Base64 编码的 JPEG，**不带** `data:image/jpeg;base64,` 前缀 */
  originalImageBase64: string
  /** 拼图块，Base64 编码的 PNG，带透明通道，同样不带前缀 */
  jigsawImageBase64: string
  /**
   * 坐标加密用的密钥。只在后端 `kuma.captcha.aes-status=true` 时非空。
   *
   * ⚠️ **它不是安全机制。** 它随响应一起下发，前端不拿到它就没法加密坐标，
   * 所以它必然对客户端可见。它只是让抓到的一次性请求不容易被照抄。
   * 真正的判定始终是服务端拿坐标与缺口位置比对。
   *
   * 为 null / undefined 时按明文坐标提交（后端的默认配置就是明文）。
   */
  secretKey?: string | null
}

/**
 * 滑块校验请求，`POST /api/captcha/check`。
 *
 * 方向是固定的：**进的是轨迹、出的是凭证。**
 */
export interface CaptchaCheckRequest {
  /** 取图接口给的会话 ID */
  captchaId: string
  /**
   * 拖动轨迹，JSON 字符串，形如 `[{"x":137,"y":5}]`。
   *
   * 后端不解析它的结构，只转交给 AJ-Captcha 比对，
   * 所以这里保持字符串而不是数组 —— 转成数组再序列化回去只会多一次
   * "两边格式不一致"的机会。
   */
  trackList: string
}

/**
 * 滑块校验结果。
 *
 * `captchaVerification` 是**一次性凭证**：拿到之后必须原样填进
 * 注册 / 登录 / 发码请求的 `captchaVerification` 字段，
 * 由业务接口完成真正的校验与消费（见后端 `CaptchaVerifier#challenge` / `#verify` 的分工）。
 *
 * ⚠️ 它只能被消费一次。用户在表单页停留期间不要把它用在两处，
 * 也**不要**因为它出现过一次就长期缓存 —— 一次失败提交之后要重新走一遍滑块，
 * 这正是 `SliderCaptcha` 暴露 `refresh()` 的原因。
 */
export interface CaptchaVerificationResult {
  captchaVerification: string
}
