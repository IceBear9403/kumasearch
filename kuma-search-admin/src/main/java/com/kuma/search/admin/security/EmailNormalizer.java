package com.kuma.search.admin.security;

import java.util.Locale;

/**
 * 邮箱归一化：<b>写入与查询两侧都必须过它</b>。
 *
 * <h3>为什么这不是"顺手 trim 一下"，而是一条硬性要求</h3>
 * 邮件地址的 {@code @} 之前部分在 RFC 5321 里理论上大小写敏感，但实际上
 * 所有主流服务商（Gmail、QQ、163、Outlook……）都按<b>大小写不敏感</b>处理，
 * 并且允许忽略点号等本地约定。也就是说 {@code Foo@x.com} 和 {@code foo@x.com}
 * 在用户和邮件服务商眼里是同一个邮箱。
 *
 * <p>而 MySQL 的默认排序规则（{@code utf8mb4_general_ci}）恰好也是大小写不敏感的，
 * 于是这里有一个非常隐蔽的组合故障：
 * <ul>
 *   <li><b>不归一化就写入</b>：用户注册时填 {@code Foo@x.com}，库里存的是原样。
 *       之后他用 {@code foo@x.com} 登录，唯一索引因为 {@code ci} 排序规则<b>仍然命中</b>，
 *       但代码里若用 {@code eq(email, "foo@x.com")} 去查、而库里的值大小写不同，
 *       行为就取决于排序规则是否一路 {@code ci} 到最后 —— 只要有一处用了二进制比较
 *       （比如某个手写的 {@code BINARY} 查询、或将来换了排序规则），
 *       结果就是「验证码收到了，但提示邮箱不存在」。</li>
 *   <li><b>唯一索引也拦不住</b>：索引的不敏感性依赖排序规则，而排序规则是可以被
 *       {@code ALTER TABLE} 改掉、也可以在部署时不同实例不相同的东西。
 *       把「同一个邮箱只有一个账号」这件事<b>只</b>寄托在排序规则上，
 *       等于让一条业务约束依赖于一个环境配置。</li>
 * </ul>
 * 因此归一化在应用层做一次，数据库层再靠唯一索引兜底，两道都要有。
 *
 * <h3>只做两件事：trim + 转小写</h3>
 * 刻意<b>不做</b>「去点号」「去 {@code +} 后缀」这类 Gmail 特有的等价规则：
 * 它们不是通用标准，对非 Gmail 邮箱应用它们会把两个真实不同的地址折叠成一个，
 * 那是比不归一化更糟的数据损坏。{@link Locale#ROOT} 而不是默认 Locale ——
 * 土耳其语环境下 {@code "I".toLowerCase()} 会得到无点的 {@code ı}，
 * 同一个邮箱在不同时区的服务器上会归一化成不同的值。
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /**
     * 归一化邮箱。
     *
     * @param raw 原始输入，可为 null
     * @return 归一化结果；输入为 null 或全空白时返回 {@code null}
     *         （<b>不</b>返回空串：调用方用 {@code == null} 判断"没有邮箱"，
     *         返回空串会让这个判断静默失效，而空串是能通过不少非空校验的）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * 两个邮箱是否指向同一个地址（按归一化后的结果比较）。
     *
     * <p>供业务侧做"注册时填的邮箱与后来登录用的是不是同一个"这类判断，
     * 避免各处自行 {@code equalsIgnoreCase} —— 那样每处都得记得先 trim。
     */
    public static boolean sameMailbox(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        return left != null && left.equals(right);
    }
}
