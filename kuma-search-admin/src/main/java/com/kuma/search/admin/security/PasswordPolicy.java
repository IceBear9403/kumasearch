package com.kuma.search.admin.security;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;

/**
 * 注册密码强度校验。
 *
 * <h3>为什么现在才补</h3>
 * 在只有密码登录、账号由管理员在后台创建的项目里，"密码由谁定"这件事本身
 * 就是一道管理约束，复杂度校验的收益不大。注册接口一开，任何人都能自助设密码，
 * 这条约束才真正开始起作用 —— 它防的是「用户把密码设成 123456」这个最大宗的风险，
 * 而不是理论上的强密码要求。
 *
 * <h3>校验发生在注册链路，且必须在散列之前</h3>
 * 不合规的密码<b>不应当被 BCrypt 计算一次</b>：每次 BCrypt 是刻意设计得昂贵的
 * （强度 12 约 200~300ms），拿一个明显不合规的输入去消耗它，等于给了一条
 * 廉价的计算资源消耗路径。这条在 {@code RegisterService} 里的顺序是有意的。
 *
 * <h3>这里刻意不做的事</h3>
 * <ul>
 *   <li><b>不查弱密码字典</b>。那需要一份可持续维护的字典（本项目连敏感词库
 *       都还在等初始数据，见 {@code docs/08-roadmap.md} §6.1），
 *       而一份不更新的弱密码字典带来的安全感大于实际收益。</li>
 *   <li><b>不禁止"密码与用户名相同"</b>。这类规则在用户真正想绕过时毫无作用，
 *       却会在 UI 上到处弹提示。真正兜底的是登录侧的 {@code LoginAttemptGuard}——
 *       它限制的是「猜的次数」，而不是「密码长什么样」，这才是对抗爆破的正确位置。</li>
 *   <li><b>不强制特殊符号</b>。要求特殊符号会显著推高"用户把密码写在便签上"的比例，
 *       而长度与字符种类的要求已经能挡掉绝大多数自动化撞库。</li>
 * </ul>
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** 最短长度。 */
    public static final int MIN_LENGTH = 8;

    /**
     * 最长长度。
     *
     * <p>128 而不是 BCrypt 实际使用的 72 字节：超过 72 字节的部分会被 BCrypt
     * 直接丢弃，但<b>在用户看来它是密码的一部分</b>。允许输入到 128 并明确拒绝
     * 更长的，比静默忽略掉一半输入更不容易让人困惑。
     */
    public static final int MAX_LENGTH = 128;

    /** 规则的中文描述，供报错文案与前端提示共用，避免两处各写一份必然漂移。 */
    public static final String RULE_DESCRIPTION =
            "密码长度需在 " + MIN_LENGTH + "~" + MAX_LENGTH + " 之间，且至少包含字母与数字";

    /**
     * 校验密码合规性。
     *
     * <p>规则：长度为 {@value #MIN_LENGTH}~{@value #MAX_LENGTH}，
     * 且同时包含字母与数字。
     *
     * <p>用「字母 + 数字」而不是「大写 + 小写 + 数字 + 特殊符号」：
     * 后者的收益在长度达标（≥8）之后已经很小，而用户为满足它付出的代价
     * （写下来、复用同一个密码）反而更伤。
     *
     * @param raw 明文密码
     * @throws BizException 参数错误。文案里<b>只说明规则、不回显密码本身</b> ——
     *                      错误信息会进日志与前端提示，把用户输入的密码带进去
     *                      等于亲手制造一次明文密码泄露
     */
    public static void validate(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ResultCode.PASSWORD_TOO_WEAK, "密码不能为空");
        }
        if (raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) {
            throw BizException.of(ResultCode.PASSWORD_TOO_WEAK, RULE_DESCRIPTION);
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw BizException.of(ResultCode.PASSWORD_TOO_WEAK, RULE_DESCRIPTION);
        }
    }
}
