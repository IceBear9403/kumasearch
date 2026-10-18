package com.kuma.search.admin.mail;

/**
 * 邮件发送接口。
 *
 * <h3>为什么是接口，而不是直接依赖 {@code JavaMailSender} 或某个云厂商 SDK</h3>
 * 接口在这里的作用是隔离<b>发信方式的选择</b>。当前有两个实现：
 * {@link AliyunDirectMailSender}（阿里云 DirectMail，已备案域名 + 控制台配好的发信地址，
 * 见 {@code docs/07-deployment.md} §十二）与 {@link SpringMailSender}（标准 SMTP），
 * 由 {@code kuma.auth.email.provider} 二选一。将来换成别的服务商、或在测试环境
 * 换成"打日志不发信"，同样只换实现类，{@code EmailCodeService} 一行不动。
 *
 * <p>如果直接依赖某个具体实现，这层选择就只能落到 {@code EmailCodeService} 里
 * 一堆 {@code if (provider.equals(...))} 上 —— 而它本该只关心"验证码怎么签发与校验"。
 *
 * <p><b>两个实现是互斥装配，不是优先级关系。</b>{@code EmailCodeService} 用的是
 * {@code ObjectProvider#getIfAvailable()}，同时存在两个 {@code MailSender} 会抛
 * {@code NoUniqueBeanDefinitionException}；因此每加一个实现，都必须给它一个
 * 与其它实现互斥的 {@code @ConditionalOnProperty}，并且有一侧带 {@code matchIfMissing}。
 * 这不是随手加的注解，是本项目"Bean 数量即契约"的一个具体位置。
 *
 * <h3>发信失败必须抛异常，不要吞掉</h3>
 * 这是与 {@code HotWordService} 那类"附属能力"相反的要求：验证码邮件发不出去，
 * 用户就永远等不到那 6 位数字，而如果这里静默返回，
 * 接口会回一个"发送成功"，用户守着邮箱等到验证码过期。
 * <b>把失败如实报出来</b>，用户才知道要重试或换方式。
 */
public interface MailSender {

    /**
     * 发送一封邮件。
     *
     * @param message 邮件内容。实现方可以假定 {@code toAddress} 已归一化
     * @throws com.kuma.search.common.exception.BizException 发送失败
     *         （凭据错误、额度耗尽、发信域名未验证等）。
     *         实现方<b>必须把厂商的原始错误码映射进来</b>，
     *         否则运维看到"发送失败"时无从判断是配额没了还是 AK 过期了
     */
    void send(MailMessage message);

    /**
     * 本实现能否把图片作为邮件的一部分内联发出（CID）。
     *
     * <h3>为什么这是一个接口方法，而不是"大家都支持"</h3>
     * 邮件里的品牌图标如果不内联，就只剩外链一条路 —— 而 Gmail 与 Outlook 默认
     * <b>不加载</b>远程图片，收件人看到的是一个灰色占位框。因此模板需要知道
     * 这一侧的能力，才能决定"画一个图标"还是"只放站点名"：
     * {@code EmailCodeTemplate#render} 拿到 {@code false} 时会渲染成纯文字页头。
     *
     * <p>这比"发出去再看效果"可靠得多。各家发信方式对 CID 的支持并不一致：
     * {@link SpringMailSender}（SMTP）支持；{@link AliyunDirectMailSender} 走的
     * {@code SingleSendMail} 没有内联图片参数，正文里的图片只能是外链。
     *
     * <p>返回 {@code false} 的实现如果收到带 {@link MailMessage#getInlineImages()} 的消息，
     * <b>必须明确报错</b>（而不是静默丢弃）：静默丢弃的后果是页头那个图标变成一个
     * 永远加载不出来的灰框，而日志里什么也看不出来。
     */
    boolean supportsInlineImages();
}
