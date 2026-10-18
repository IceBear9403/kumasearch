package com.kuma.search.admin.mail;

import java.util.Objects;

/**
 * 需要随邮件一起发出的内联图片（CID 方式）。
 *
 * <h3>为什么是内联而不是外链</h3>
 * 邮件里的 {@code <img src="https://...">} 会被多数客户端默认**不加载**：
 * Gmail、Outlook 的新账号都会把远程图片挡在一个灰色占位框后面，等收件人点
 * 「显示图片」。而一封验证码邮件的全部价值就在于"一眼看到那 6 位数字"，
 * 一个需要用户额外点一次才正常的品牌图标不值这个交互成本。
 *
 * <p>CID（{@code Content-ID}）把图片作为邮件本体的一部分发出去，客户端无需再取任何外部资源。
 *
 * <h3>data URI 与 CSS 画图都不行</h3>
 * {@code data:image/png;base64,...} 被 Gmail 与 Outlook 直接屏蔽；
 * 用 CSS 的 {@code border-radius} 拼一个圆点图案在 Outlook 桌面版会退化成方块
 * （它用 Word 的排版引擎，不支持圆角）。因此只有 PNG + CID 这一条路。
 *
 * @param contentId   正文里 {@code cid:} 之后的那一段标识。只允许 ASCII 字母数字与连字符
 *                    （RFC 2392 的 {@code addr-spec} 对这块有约束，中文或空格会让部分客户端解析失败）
 * @param contentType MIME 类型，如 {@code image/png}。**必须显式给出** ——
 *                    靠文件名后缀推断在 jar 里不总是可靠
 * @param data        图片字节
 */
public record InlineImage(String contentId, String contentType, byte[] data) {

    public InlineImage {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new IllegalArgumentException("内联图片内容为空：contentId=" + contentId);
        }
    }
}
