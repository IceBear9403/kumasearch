package com.kuma.search.admin.mail;

import com.kuma.search.admin.dto.EmailScene;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验证码邮件的 HTML 渲染。
 *
 * <h3>占位符</h3>
 * 模板是 {@code resources/mail/email-code.html}，其中的双花括号占位符在发送时被替换。
 * 允许出现的键**只有**下面这些，多一个少一个都会在启动时失败：
 *
 * <table border="1">
 *   <caption>模板占位符</caption>
 *   <tr><th>键</th><th>值</th></tr>
 *   <tr><td>{@code siteName}</td><td>站点名（{@link #SITE_NAME}）</td></tr>
 *   <tr><td>{@code sceneLabel}</td><td>{@link EmailScene#label()}，如「登录」</td></tr>
 *   <tr><td>{@code sceneIntro}</td><td>场景说明句，如「您正在登录 KumaSearch…」</td></tr>
 *   <tr><td>{@code code}</td><td>6 位验证码</td></tr>
 *   <tr><td>{@code minutes}</td><td>有效期分钟数</td></tr>
 *   <tr><td>{@code year}</td><td>当前年份</td></tr>
 *   <tr><td>{@code logo}</td><td>品牌图标标记（<b>不转义的 HTML 片段</b>，见 {@link #render}）；发送方不支持内联图片时为空串</td></tr>
 * </table>
 *
 * <h3>⚠️ 替换值一律做 HTML 转义，这是"支持 HTML 正文"的前提</h3>
 * {@link MailMessage} 早期刻意只支持纯文本，理由是"支持 HTML 会让邮件正文变成
 * 一个需要转义与消毒的输入面"。加上 HTML 之后，那条理由靠下面两件事守住：
 * <ol>
 *   <li>模板是<b>固定文件</b>，不在运行期拼接；</li>
 *   <li>所有替换值都过 {@link #escapeHtml} —— 即便将来某个值里混进了用户可控内容
 *       （例如把昵称放进问候语），它也只能显示成文本，不会变成标签。</li>
 * </ol>
 * <b>不要图省事给某个键开"原样输出"的口子</b>：那一个口子就是一次注入，
 * 而注入发生在邮件里，意味着攻击者可以在我们的域名下伪装成我们的通知。
 * 唯一的例外是 {@code {{logo}}}，理由（以及它为什么仍然安全）写在 {@link #render} 里。
 *
 * <h3>错误一律在启动时暴露</h3>
 * 模板文件缺失、占位符拼错、某个占位符被从模板里删掉 —— 全部在启动阶段发现并抛异常。
 * 理由与 {@code SpringMailSender#validateConfig} 相同：这些是"部署时就该发现"的错误，
 * 拖到运行时的表现是「用户点获取验证码，报发送失败」，而那时没人在看启动日志。
 */
@Slf4j
@Component
public class EmailCodeTemplate {

    /** 模板在 classpath 上的路径。 */
    private static final String TEMPLATE_PATH = "mail/email-code.html";

    /** 品牌图标在 classpath 上的路径。 */
    private static final String LOGO_PATH = "mail/bear-paw.png";

    /**
     * 图标的 {@code Content-ID}。
     *
     * <p>只用 ASCII 字母与连字符：它最终会出现在 {@code cid:} 与 MIME 头里，
     * 而部分客户端对非 ASCII 的 content-id 处理不一致。
     */
    private static final String LOGO_CONTENT_ID = "kuma-bear-paw";

    private static final String LOGO_CONTENT_TYPE = "image/png";

    /** 品牌图标在邮件里的显示尺寸。源图 144×144，即 3 倍图，高分屏下不糊。 */
    private static final int LOGO_DISPLAY_SIZE = 40;

    /** 占位符语法：两个花括号包一个驼峰键名。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}");

    /**
     * 站点名。
     *
     * <p>写成常量而不是读配置：它同时出现在页头、页脚与文案里，是一个品牌事实，
     * 不是部署参数。真正需要按环境变的是 {@code kuma.auth.email.from-alias}
     * （发件人显示名），那是另一回事。
     */
    private static final String SITE_NAME = "KumaSearch";

    /**
     * 场景说明句。
     *
     * <p>每个场景的文案都不同，因为收件人的处境不同：注册的人正在等一封"这邮箱能用"的确认，
     * 登录的人则是自己在等，换绑的人需要知道"这封信是发给新邮箱的、旧邮箱会失效"。
     * 写成同一句「您正在使用本服务」虽然省事，
     * 但会让用户在下一次收到仿冒同一模板的钓鱼邮件时分辨不出真假。
     *
     * <p>⚠️ 这是个 {@code Map.of(...)} 字面量，<b>少一个场景不会编译报错</b>：
     * {@code get} 会返回 null，渲染时替换进模板就成了 "null"（或在拼接处抛 NPE）。
     * 新增 {@link EmailScene} 取值时<b>必须</b>同步在这里加一项 ——
     * {@code EmailCodeTemplate} 里有一个自检方法会遍历全部场景渲染一遍，就是为了兜住它。
     */
    private static final Map<EmailScene, String> SCENE_INTRO = Map.of(
            EmailScene.REGISTER,
            "您正在注册 " + SITE_NAME + " 账号，请在注册页输入下面的验证码完成验证。",
            EmailScene.LOGIN,
            "您正在登录 " + SITE_NAME + "，请在登录页输入下面的验证码完成验证。",
            EmailScene.CHANGE_EMAIL,
            "您正在为 " + SITE_NAME + " 账号换绑这个邮箱，"
                    + "换绑成功后旧邮箱将无法再用于登录。请在个人中心输入下面的验证码完成换绑。");

    /** 允许出现在模板里的键，与类注释里的表格一一对应。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "siteName", "sceneLabel", "sceneIntro", "code", "minutes", "year", "logo");

    private final String template;
    private final byte[] logoBytes;

    public EmailCodeTemplate() {
        this.template = readClasspathText(TEMPLATE_PATH);
        this.logoBytes = readClasspathBytes(LOGO_PATH);
    }

    /**
     * {@link #render} 的结果：HTML 正文，以及它引用的内联图片。
     *
     * @param htmlBody     完整的 HTML 文档
     * @param inlineImages 需要以 CID 方式随邮件发出的图片；发送方不支持时为空列表
     */
    public record Content(String htmlBody, List<InlineImage> inlineImages) {
        public Content {
            inlineImages = List.copyOf(inlineImages);
        }
    }

    /**
     * 渲染一封验证码邮件。
     *
     * @param withLogo 发送方是否支持内联图片（见 {@link MailSender#supportsInlineImages()}）。
     *                 为 {@code false} 时 {@code {{logo}}} 渲染成空串 ——
     *                 页头仍是一个完整的站点名，只是没有图标
     */
    public Content render(EmailScene scene, String code, int minutes, boolean withLogo) {
        String html = template;
        for (Map.Entry<String, String> entry : Map.of(
                "siteName", SITE_NAME,
                "sceneLabel", scene.label(),
                "sceneIntro", SCENE_INTRO.get(scene),
                "code", code,
                "minutes", String.valueOf(minutes),
                "year", String.valueOf(LocalDate.now().getYear())).entrySet()) {
            // 取值一律转义（见类注释）；因为转义后的值里不可能再出现占位符语法，
            // 逐个 replace 不存在"替换结果被二次替换"的问题，与顺序无关。
            html = html.replace("{{" + entry.getKey() + "}}", escapeHtml(entry.getValue()));
        }

        // ⚠️ {{logo}} 刻意**不走** escapeHtml，这是上面那条"一律转义"的唯一例外。
        // 理由是它不是数据，而是模板自身的一部分：由 logoMarkup() 用本类的常量拼出的
        // HTML 片段，没有任何外部输入能影响它的内容。转义它反而是错的 ——
        // 结果是收件人看到 `&lt;img src=&quot;cid:...&quot;&gt;` 这串标签源码
        // 摆在页头上，而日志里一切正常（这是只有真机上才看得出来的错误）。
        // 要往这个片段里加变量之前，先回来看这段注释：
        // 那一刻它就从"模板的一部分"变成"数据"，必须改回转义后的方案
        // （例如把图标尺寸之类的可变量单独做成数据键，而不是拼进这段 HTML）。
        html = html.replace("{{logo}}", withLogo ? logoMarkup() : "");

        if (html.contains("{{")) {
            // 走到这里说明模板里有个占位符既没被替换、又不是上面那七个键之一。
            // validatePlaceholders 挡的是"键名不认识"，这里挡的是"花括号写法不对"，
            // 例如多了一个空格、或只写了一半。
            throw new IllegalStateException(
                    "验证码邮件模板渲染后有未替换的占位符（检查 " + TEMPLATE_PATH
                            + " 的花括号写法：必须是紧邻的双花括号 + 上表中的键名）。");
        }

        List<InlineImage> images = withLogo
                ? List.of(new InlineImage(LOGO_CONTENT_ID, LOGO_CONTENT_TYPE, logoBytes))
                : List.of();
        return new Content(html, images);
    }

    /**
     * 启动自检一：模板里的占位符集合是否正好是允许的那七个。
     *
     * <p>这一类错误的共同点是<b>只有真正发信时才会暴露</b>：拼错的键名会让用户看到
     * 原始占位符文本，被删掉的键会让邮件里那一处变成空白 —— 而"验证码那一格是空的"
     * 这种故障，日志里毫无痕迹。
     */
    @PostConstruct
    void validatePlaceholders() {
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalStateException(
                        "验证码邮件模板里有未知占位符：" + key + "（允许的键：" + ALLOWED_KEYS + "）。"
                                + "新增占位符时必须同时在 EmailCodeTemplate#render 里给出取值。");
            }
        }
        for (String required : ALLOWED_KEYS) {
            if (!template.contains("{{" + required + "}}")) {
                throw new IllegalStateException(
                        "验证码邮件模板缺少必需的占位符：" + required + "（允许的键：" + ALLOWED_KEYS + "）。"
                                + "从模板里删掉一个占位符不会报错，只会让邮件里那一处变成空白。");
            }
        }
    }

    /**
     * 启动自检二：用样例数据把两种场景 × 有无图标四种组合都渲染一遍。
     *
     * <p>与上一个自检的分工：那里校验的是"模板里写了什么"，这里校验的是"渲染出来对不对"
     * —— 例如模板整体被换掉、或替换逻辑本身出了问题（渲染结果里连验证码都不见了）。
     */
    @PostConstruct
    void selfCheck() {
        for (EmailScene scene : EmailScene.values()) {
            for (boolean withLogo : new boolean[]{true, false}) {
                Content content = render(scene, "000000", 5, withLogo);
                if (!content.htmlBody().contains("000000")) {
                    throw new IllegalStateException(
                            "验证码邮件模板自检失败：渲染结果里找不到验证码本身（" + TEMPLATE_PATH + "）。");
                }
                if (content.htmlBody().contains("{{")) {
                    throw new IllegalStateException(
                            "验证码邮件模板自检失败：渲染结果里残留占位符（" + TEMPLATE_PATH + "）。");
                }
                // 图标必须是真的 <img> 标签，而不是被转义成文本的标签源码。
                // 这正是 {{logo}} 绕过 escapeHtml 的原因（见 render 里的注释），
                // 一旦有人"顺手"把它并回统一的转义循环，这条断言会在启动时就拦下来 ——
                // 否则那个错误的唯一表现是在收件人的邮箱里，而日志一切正常。
                if (withLogo) {
                    if (!content.htmlBody().contains("cid:")
                            || content.htmlBody().contains("&lt;img")) {
                        throw new IllegalStateException(
                                "验证码邮件模板自检失败：品牌图标没有被渲染成 HTML 标签"
                                        + "（应含 cid: 、且不含 &lt;img）。见 EmailCodeTemplate#render 里"
                                        + "关于 {{logo}} 不走 escapeHtml 的说明。");
                    }
                    if (content.inlineImages().size() != 1) {
                        throw new IllegalStateException(
                                "验证码邮件模板自检失败：带图标的正文必须恰好内联 1 张图片，实际 "
                                        + content.inlineImages().size() + " 张。");
                    }
                } else if (!content.inlineImages().isEmpty()) {
                    throw new IllegalStateException(
                            "验证码邮件模板自检失败：不带图标的正文不应内联任何图片。");
                }
            }
        }
        log.info("验证码邮件模板已加载：path={} 模板={} 字符，品牌图标={} 字节",
                TEMPLATE_PATH, template.length(), logoBytes.length);
    }

    /** 页头里的品牌图标。 */
    private static String logoMarkup() {
        return "<img src=\"cid:" + LOGO_CONTENT_ID + "\" alt=\"" + SITE_NAME + "\""
                + " width=\"" + LOGO_DISPLAY_SIZE + "\" height=\"" + LOGO_DISPLAY_SIZE + "\""
                + " style=\"display:inline-block;vertical-align:middle;margin-right:10px;"
                + "width:" + LOGO_DISPLAY_SIZE + "px;height:" + LOGO_DISPLAY_SIZE
                + "px;border:0;outline:none;\">";
    }

    /**
     * HTML 转义。
     *
     * <p>与 {@code HtmlUtils.htmlEscape} 等价，但刻意不引 Spring 的工具类：
     * 这段逻辑是"邮件正文不能被注入"这条约束的落点，值得一眼看全。
     */
    private static String escapeHtml(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readClasspathText(String path) {
        return new String(readClasspathBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * 读 classpath 资源。
     *
     * <p>读不到时抛异常而不是返回空：模板缺失的情况下"继续启动"没有任何意义 ——
     * 注册与邮箱码登录都会在第一次被使用时失败，而且带着一句笼统的"邮件发送失败"，
     * 让人往 SMTP 那边查。
     */
    private static byte[] readClasspathBytes(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "邮件资源不存在：classpath:" + path
                            + "。它必须随 kuma-search-admin 一起打包（src/main/resources 下）。");
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取邮件资源失败：classpath:" + path, e);
        }
    }
}
