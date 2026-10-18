package com.kuma.search.admin.captcha;

import com.kuma.search.admin.dto.CaptchaChallengeResponse;

/**
 * 滑块验证码（人机校验）的校验入口。
 *
 * <h3>⚠️ 「未启用」的语义是拒绝服务，不是跳过校验</h3>
 * 实现方在 {@code kuma.captcha.enabled=false} 时必须<b>抛异常</b>，
 * 而不是安静地放行。理由：一个"关掉就等于不校验"的开关，上线时忘了打开
 * 不会产生任何可观察的错误 —— 没有报错、没有日志、功能看起来完全正常，
 * 只是少了一道防线。而少的那道防线背后是真金白银的邮件额度与可被脚本刷的登录接口。
 *
 * <p>选「不打开就用不了」之后，配置遗漏变成一个<b>必须当场解决</b>的问题：
 * 用户点注册会立刻看到"服务未开通"，运维在五分钟内就会知道。
 *
 * <h3>这道防线能挡住什么、挡不住什么</h3>
 * 挡得住：无脑脚本、爬虫、只改参数不改流程的自动化工具 —— 也就是绝大多数实际流量。
 *
 * <p><b>挡不住：打码平台。</b>针对滑块拼图的打码服务是成熟产业，成本低到可以忽略。
 * 因此滑块只是「抬高成本」，真正兜底的是它后面那几层：
 * 发码接口的 IP 限流与邮箱日配额、每日全局配额、以及登录侧的
 * {@code LoginAttemptGuard}。不要把"加了验证码"当成"已经安全了"。
 */
public interface CaptchaVerifier {

    /**
     * 出题：生成一次滑块挑战（背景图 + 拼图块 + 会话 ID）。
     *
     * <p>对应 {@code GET /api/captcha/get}。它只是"把题目发下去"，
     * 正确坐标留在服务端（存在 Redis 里，见 {@code RedisCaptchaCacheService}），
     * <b>绝不随响应下发</b> —— 那等于把答案写给客户端。
     *
     * <p>挑战数据有 TTL（上游固定 2 分钟），过期后再提交会被判为
     * 「验证码已失效，请重新获取」。这是正常的时效，不是错误配置。
     *
     * @return 渲染一次滑块所需的全部数据
     * @throws com.kuma.search.common.exception.BizException
     *         验证码功能未启用（{@code SYSTEM_ERROR}），
     *         或底图加载失败、Redis 不可用等（{@code SYSTEM_ERROR}）
     */
    CaptchaChallengeResponse createChallenge();

    /**
     * 一级校验：校验滑块的<b>轨迹</b>，通过则签发一枚一次性凭证交给业务接口。
     *
     * <p>对应 {@code POST /api/captcha/check}。它<b>不消费任何东西</b> ——
     * 此时还没有凭证可消费，凭证是它的返回值。
     *
     * <p><b>为什么必须是两个方法而不是一个。</b>滑块校验在服务端是两段：
     * 一段看「这次拖动的轨迹和缺口位置对得上吗」（成本高、需要图与轨迹），
     * 一段看「业务请求带来的这枚凭证是不是真的、有没有被用过」（成本低、只需凭证本身）。
     * 业务接口只做第二段，取图接口只做第一段，中间由凭证传递。
     * 若合成一个方法，{@code /api/captcha/check} 就会把凭证消费掉，
     * 而紧随其后的注册请求拿着同一枚凭证再来校验时会判定为「已使用」——
     * {@link #verify} 的注释里说的「凭证不可重放」和这里说的「业务必须带凭证」
     * 会变成一对无法同时满足的要求。
     *
     * @param captchaId 本次滑块的会话 ID，由取图接口返回；用凭证换取式实现的版本可能不需要
     * @param trackList 前端采集的拖动轨迹；同上，与 {@code captchaVerification} 二选一
     * @return 一次性凭证，前端须原样交给注册/登录/发码接口的 {@code captchaVerification} 字段
     * @throws com.kuma.search.common.exception.BizException
     *         轨迹校验不通过（{@code CAPTCHA_INVALID}）、
     *         参数不足，或验证码功能未启用（{@code SYSTEM_ERROR}）
     */
    String challenge(String captchaId, String trackList);

    /**
     * 二级校验：校验业务请求带来的凭证，并在校验通过后<b>立即消费</b>它。
     *
     * <p>两个动作必须是一体的：把「校验」和「消费」拆成两个方法暴露给调用方，
     * 迟早会有调用方只调前者（因为它"看起来完成了校验"），
     * 于是凭证就可以被重放 —— 而那种调用方从代码上看不出任何问题。
     * 注意这里是<b>二级</b>校验内部的两步，与 {@link #challenge} 的两段结构无关：
     * 那段分工是「哪个接口做哪件事」，这段是「一件事必须做完整」。
     *
     * @param captchaVerification 前端从 {@link #challenge} 拿到、并随业务请求带回的凭证
     * @throws com.kuma.search.common.exception.BizException
     *         凭证无效、已过期、已被使用过（{@code CAPTCHA_INVALID}），
     *         或验证码功能未启用（{@code SYSTEM_ERROR}）
     */
    void verify(String captchaVerification);

    /**
     * 验证码功能是否可用。
     *
     * <p>供健康检查、取图接口与启动自检使用。<b>业务接口不应据它决定"要不要跳过校验"</b> ——
     * 该判断只在 {@link #verify} 内部发生，业务侧一律直接调用 {@code verify}。
     */
    boolean isEnabled();
}
