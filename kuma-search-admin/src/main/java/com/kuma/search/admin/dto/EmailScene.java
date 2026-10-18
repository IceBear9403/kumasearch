package com.kuma.search.admin.dto;

import com.kuma.search.common.exception.BizException;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 邮箱验证码的使用场景。
 *
 * <h3>为什么场景必须显式区分，而不是「一个验证码通用」</h3>
 * {@code scene} 会进入 Redis key（见 {@code AuthKeys#emailCode}）。
 * 如果注册与登录共用一枚验证码，就会出现这条攻击链：
 * <ol>
 *   <li>攻击者用自己的邮箱在<b>注册页</b>拿到一枚验证码（这一步完全合法，他有自己的邮箱）；</li>
 *   <li>拿这枚验证码去调<b>登录</b>接口，试试能不能命中另一个邮箱的账号；</li>
 *   <li>更糟的是反过来 —— 用登录场景发出的码去完成注册，
 *       或在两个不同的业务意图之间搬运凭证。</li>
 * </ol>
 * 场景进 key 之后，一枚码只能用在它被发出的那个场景里，跨场景重放直接查不到记录。
 * 这是邮箱验证码实现中最经典也最容易漏掉的一条。
 *
 * <h3>与「是否泄露邮箱是否存在」的关系</h3>
 * 这个枚举还决定发码接口的<b>响应策略</b>：{@link #REGISTER} 可以明确说「该邮箱已注册」
 * （注册页本就需要这个提示），而 {@link #LOGIN} 必须对「邮箱存在」与「不存在」
 * 返回完全相同的响应 —— 否则接口就成了批量探测「哪些邮箱是本站用户」的工具。
 * 两者写在同一个枚举里，是为了让这处不对称在代码结构上可见，
 * 而不是藏在某个 if 分支里等着被后人"统一"掉。
 */
@Schema(description = "邮箱验证码使用场景")
public enum EmailScene {

    /**
     * 注册。邮箱此时应当<b>尚未</b>被占用。
     *
     * <p>已占用时明确报错：注册页必须提示用户"这个邮箱已经注册过了，去登录"，
     * 否则用户会反复重试一个永远不可能成功的操作。
     */
    @Schema(description = "注册场景：邮箱此时应尚未被占用")
    REGISTER("注册"),

    /**
     * 邮箱码登录。邮箱是否已注册<b>绝不可泄露</b>。
     */
    @Schema(description = "登录场景：不得泄露邮箱是否已注册")
    LOGIN("登录"),

    /**
     * 换绑邮箱 —— 已登录用户把账号邮箱改成另一个。
     *
     * <p>验证码发到<b>新邮箱</b>，因为它要证明的是「这个新邮箱是你能收信的」；
     * 发到旧邮箱只能证明"账号是你的"，那是登录态已经证明过的事。
     *
     * <p>它允许明确告知「该邮箱已被占用」，与 {@link #LOGIN} 的不可探测性不冲突：
     * LOGIN 防的是<b>匿名</b>探测「哪些邮箱是本站用户」，而这里发起者已经登录、
     * 身份确定，不给出提示只会让用户在一个永远不可能成功的操作上反复重试。
     */
    @Schema(description = "换绑邮箱场景：新邮箱应尚未被占用")
    CHANGE_EMAIL("换绑邮箱");

    private final String label;

    EmailScene(String label) {
        this.label = label;
    }

    /** 中文场景名，用于邮件主题与文案。 */
    public String label() {
        return label;
    }

    /**
     * 解析前端传来的场景字符串。
     *
     * <p>大小写不敏感（前端可能传 {@code register}），但<b>不接受未知值</b>：
     * 未知场景直接报参数错误，而不是退化成某个默认场景 ——
     * 默认到 {@code LOGIN} 会让注册流程拿不到它需要的"邮箱已注册"提示，
     * 默认到 {@code REGISTER} 则更糟：它把一个探测接口带进了登录链路。
     *
     * @throws BizException 场景为空或不是已知取值
     */
    public static EmailScene parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.paramInvalid("scene 不能为空");
        }
        String normalized = raw.trim().toUpperCase();
        for (EmailScene scene : values()) {
            if (scene.name().equals(normalized)) {
                return scene;
            }
        }
        // 文案里逐个列出场景，而不是写「取值非法」：新增场景时这句话会立刻显得不对，
        // 从而逼着改的人回头确认上面那几条分支是否也要跟着改（历史上这是漏得最多的一处）。
        throw BizException.paramInvalid("scene 取值非法，只支持 REGISTER / LOGIN / CHANGE_EMAIL");
    }
}
