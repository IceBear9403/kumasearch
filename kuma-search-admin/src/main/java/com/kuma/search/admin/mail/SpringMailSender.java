package com.kuma.search.admin.mail;

import com.kuma.search.common.exception.BizException;
import com.kuma.search.common.result.ResultCode;
import com.kuma.search.config.KumaSearchProperties;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 标准 SMTP 的 {@link MailSender} 实现（{@code kuma.auth.email.provider=smtp} 时装配）。
 *
 * <h3>与 {@link AliyunDirectMailSender} 的分工</h3>
 * 两者是<b>互斥</b>的两个实现，不是"谁优先"的关系 —— 同时存在两个 Bean 会让
 * {@code EmailCodeService} 的 {@code ObjectProvider#getIfAvailable()} 抛
 * {@code NoUniqueBeanDefinitionException}，且是启动时才炸。因此条件必须成对出现：
 * 本类要 {@code provider=smtp}，DirectMail 那侧要 {@code provider=aliyun} 且
 * {@code matchIfMissing=true}（保持既有部署不配这一项时的行为不变）。
 *
 * <p>什么时候选 SMTP：已经在用企业邮箱 / 自建邮件服务，不想开通云厂商的发信服务。
 * 代价是<b>投递率要自己负责</b> —— DirectMail 那侧由厂商托管 IP 信誉与退信处理，
 * SMTP 这侧（尤其是自建 MTA 直发 25 端口）很可能被判垃圾邮件甚至直接拒收。
 * 用自有域名直发之前，SPF/DKIM/DMARC 这三条记录必须先配好，见 {@code docs/07-deployment.md}。
 *
 * <h3>配置项在 {@code spring.mail.*}，不在 {@code kuma.auth.email.*}</h3>
 * 连接参数用 Spring Boot 的标准键（{@code spring.mail.host} / {@code port} /
 * {@code username} / {@code password} / {@code properties.mail.smtp.*}），
 * 这样 TLS、认证方式、超时这些都能直接照抄任意一份 SMTP 教程，不需要本项目再定义一遍。
 * 只有<b>发件人地址与显示名</b>取 {@code kuma.auth.email.*}：它与 DirectMail 侧共用同一组配置，
 * 切换 provider 时不必去想"发件地址这次配在哪"。
 *
 * <h3>⚠️ 发件地址必须与认证账号一致</h3>
 * 很多 SMTP 服务器会拒收 {@code From} 与登录账号不同的邮件（应答码 550/553
 * "sender address rejected" 一类）。这不是本项目的限制，是收信方的策略，
 * 因此 {@code account-name} 填的必须是那个真实可发信的地址，而不是任意别名。
 *
 * <h3>成功与失败</h3>
 * 与 DirectMail 相反（那边失败也回 HTTP 200，必须解析响应体），SMTP 的应答码本身就是契约：
 * 非 2xx 会让 {@code JavaMailSender#send} 抛 {@link MailException}。
 * 但日志必须把原始异常打全 —— 应答码里区分"额度满"和"凭据过期"的唯一线索就在里面，
 * 而用户看到的文案只能是一句笼统的话（SMTP 的原文可能带上我们的主机名与账号名）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kuma.auth.email", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "kuma.auth.email", name = "provider", havingValue = "smtp")
public class SpringMailSender implements MailSender {

    private final ObjectProvider<JavaMailSender> javaMailSenderProvider;
    private final KumaSearchProperties properties;

    /**
     * 在 {@link #validateConfig} 里从 {@code ObjectProvider} 取出来。
     *
     * <p>为什么绕一层 {@code ObjectProvider} 而不是直接注入 {@code JavaMailSender}：
     * 那个 Bean 由 Spring Boot 的 {@code MailSenderAutoConfiguration} 提供，而它只在
     * {@code spring.mail.host} 存在时才生效。直接注入的话，漏配这个键的表现是
     * 一条"找不到 JavaMailSender 类型的 Bean"的启动错误 —— 它<b>指向的是框架内部类型，
     * 而不是那个真正漏配的配置项</b>。绕一层之后，下面能给出写明键名与后果的提示。
     */
    private JavaMailSender javaMailSender;

    /** 发件人地址，取值见类注释。 */
    private String fromAddress;

    /** 发件人显示名，可以为空（为空时只发地址）。 */
    private String fromAlias;

    public SpringMailSender(ObjectProvider<JavaMailSender> javaMailSenderProvider,
                            KumaSearchProperties properties) {
        this.javaMailSenderProvider = javaMailSenderProvider;
        this.properties = properties;
    }

    /**
     * 启动时校验配置是否配齐。
     *
     * <p>必须在这里失败，而不是等用户点"获取验证码"：漏配 SMTP 是一个
     * "部署时就该发现"的错误，拖到运行时的表现是「按钮能用但每次都提示发送失败」，
     * 而那时候没有人在看启动日志。这与 {@link AliyunDirectMailSender#validateCredentials}
     * 是同一条纪律。
     */
    @PostConstruct
    void validateConfig() {
        KumaSearchProperties.Auth.Email email = properties.getAuth().getEmail();

        javaMailSender = javaMailSenderProvider.getIfAvailable();
        if (javaMailSender == null) {
            throw new IllegalStateException(
                    "kuma.auth.email.provider=smtp，但 spring.mail.host 未配置，"
                            + "Spring Boot 没有创建 JavaMailSender。"
                            + "请在 application.yml 或环境变量中补齐 spring.mail.host / port / username / password，"
                            + "见 docs/07-deployment.md 的配置项清单。");
        }

        if (!StringUtils.hasText(email.getAccountName())) {
            throw new IllegalStateException(
                    "kuma.auth.email.provider=smtp，但 account-name 未配置。"
                            + "它是发件人地址（From），必须与 spring.mail.username 指向同一个真实邮箱，"
                            + "否则多数 SMTP 服务器会以 550/553「sender address rejected」拒收。");
        }

        fromAddress = email.getAccountName();
        fromAlias = email.getFromAlias();
        log.info("验证码邮件将经 SMTP 发送：from={} alias={}", fromAddress,
                StringUtils.hasText(fromAlias) ? fromAlias : "(无)");
    }

    @Override
    public boolean supportsInlineImages() {
        // MimeMessageHelper 的 addInline 就是为这件事存在的：图片随邮件一起发出，
        // 收件端不需要再取任何外部资源，因此不受"默认不加载远程图片"的影响。
        return true;
    }

    @Override
    public void send(MailMessage message) {
        List<InlineImage> inlineImages = message.getInlineImages() == null
                ? List.of() : message.getInlineImages();

        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            // multipart 只在确实需要时才打开：只发纯文本却声明 multipart，
            // 会给每个收件端多出一层无意义的 MIME 边界。
            boolean multipart = message.getHtmlBody() != null || !inlineImages.isEmpty();
            // 编码显式传 UTF-8，不依赖 spring.mail.default-encoding：
            // 漏配那个键的默认值是 US-ASCII，中文主题会变成一串问号，
            // 而"主题乱码"这种事在日志里完全看不出来，只有收件人会发现。
            MimeMessageHelper helper =
                    new MimeMessageHelper(mimeMessage, multipart, StandardCharsets.UTF_8.name());
            helper.setTo(message.getToAddress());
            helper.setSubject(message.getSubject());
            if (message.getHtmlBody() != null) {
                // 两个参数的形式会生成 multipart/alternative：能显示 HTML 的客户端看 HTML，
                // 只认纯文本的（或被反垃圾降级呈现的）看 textBody。
                // 顺序不能反 —— 按 RFC 2046，越靠后的分支越"丰富"。
                helper.setText(message.getTextBody(), message.getHtmlBody());
            } else {
                helper.setText(message.getTextBody(), false);
            }
            for (InlineImage image : inlineImages) {
                helper.addInline(image.contentId(), new ByteArrayResource(image.data()), image.contentType());
            }
            if (StringUtils.hasText(fromAlias)) {
                helper.setFrom(fromAddress, fromAlias);
            } else {
                helper.setFrom(fromAddress);
            }
            // 刻意不设 Reply-To：这是单向通知邮箱（与 DirectMail 侧 reply-to-address=false 同义）。
            // 设了 Reply-To 等于给收件人一个入口，而那个地址没有人看，用户会以为自己的回复被收到了。
        } catch (MessagingException | UnsupportedEncodingException e) {
            // 组装阶段就失败 = 收件人地址格式非法或编码器缺失，属于"我们的调用参数有问题"，
            // 与网络/服务器侧的失败要分开记，否则运维会去查 SMTP 服务端的日志。
            log.error("SMTP 邮件组装失败：to={} subjectLength={} err={}",
                    message.getToAddress(), message.getSubject() == null ? 0 : message.getSubject().length(),
                    e.toString());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件发送失败，请稍后重试");
        }

        try {
            javaMailSender.send(mimeMessage);
        } catch (MailAuthenticationException e) {
            // 认证失败是部署配置错误，与用户操作无关，也重试不了 —— 文案要指向管理员
            log.error("SMTP 认证失败（检查 spring.mail.username / password）：from={} err={}",
                    fromAddress, e.toString());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件服务配置有误，请联系管理员");
        } catch (MailException e) {
            // 这里包含"额度/频率受限"（应答码 4xx、550 一类）与"连接超时"两种，
            // 二者的区分线索在异常自带的 SMTP 应答码里。不用类型判断来分类：
            // 那些类型在 jakarta.mail 的实现包里，跟着依赖换实现就编不过，
            // 而本项目只引了 starter、没有绑死具体实现。整条异常打全即可，
            // 运维在日志里能看到码；用户侧则统一为"稍后重试"。
            log.error("SMTP 发信失败（应答码见异常原文）：to={} from={} err={}",
                    message.getToAddress(), fromAddress, e.toString());
            throw BizException.of(ResultCode.SYSTEM_ERROR, "邮件发送失败，请稍后重试");
        }

        log.info("验证码邮件已提交 SMTP，to={}", message.getToAddress());
    }
}
