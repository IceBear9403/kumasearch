package com.kuma.search.admin.mail;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 一封待发送的邮件。
 *
 * <h3>正文：HTML 是"带约束地"支持，不是随便传字符串</h3>
 * 一度刻意只支持纯文本，理由是支持 HTML 会让正文变成一个需要转义与消毒的输入面。
 * 现在两类正文都给：{@link #htmlBody} 由 {@code EmailCodeTemplate} 从固定模板渲染，
 * 模板里的取值一律 HTML 转义（见该类的类注释）。**不要**在这里拼 HTML ——
 * 一旦有人把用户可控的字符串直接塞进 {@code htmlBody}，那条理由就重新成立了。
 *
 * <p>两类正文一起给是有实际作用的：只发 HTML 的邮件在被纯文本客户端或
 * 反垃圾系统降级呈现时是一片空白，而 {@code textBody} 是它唯一的兜底 ——
 * 一封验证码邮件的兜底版本里**必须**有那 6 位数字。
 */
@Data
@Builder
public class MailMessage implements Serializable {

    /** 收件人地址，必须是已归一化的形式（见 {@code EmailNormalizer}）。 */
    private String toAddress;

    /** 邮件主题。 */
    private String subject;

    /**
     * 纯文本正文。
     *
     * <p>有 HTML 时它是 {@code multipart/alternative} 的降级分支；没有 HTML 时
     * 它就是正文本身。**不允许为空** —— 一封既没有纯文本也没有 HTML 的邮件没有意义。
     */
    private String textBody;

    /**
     * HTML 正文，可为 {@code null}。
     *
     * <p>取值为 {@code null} 表示"这封信只有纯文本"（发信方不支持内联图片时
     * 不会被置空 —— 那种情况下降级的是图标，不是整封 HTML）。
     */
    private String htmlBody;

    /**
     * 需要以 CID 方式内联的图片，见 {@link InlineImage}。
     *
     * <p>由 {@link MailSender#supportsInlineImages()} 决定要不要带：
     * 不支持内联的实现拿到非空列表时应当明确报错，而不是静默丢掉图片
     * —— 静默丢掉的后果是收件人看到一个永远是灰框的图标。
     */
    @Builder.Default
    private List<InlineImage> inlineImages = List.of();
}
