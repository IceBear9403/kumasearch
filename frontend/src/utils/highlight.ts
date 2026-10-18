/**
 * 高亮片段解析工具
 *
 * 后端返回的高亮片段形如："华为<em>手机</em>Mate60"，其中 <em> 是 Elasticsearch
 * 用于标记命中词的标签。片段的内容来自文档正文，也就是**用户数据**，
 * 可能包含任意字符（包括 <script>）。
 *
 * ## 安全边界：切分在这里做，转义交给框架
 *
 * 本文件**只做切分，不做 HTML 转义**。转义由 Vue 的文本插值 `{{ }}` 负责 ——
 * 它保证输出到 DOM 的永远是文本节点而不是标签。
 *
 * 为什么不在切分时顺手转义：那样会**转义两次**。`parseHighlight` 把 `&` 变成 `&amp;`，
 * 插值再把它变成 `&amp;amp;`，页面上就会原样显示 "&amp;"。
 * 这曾经是本项目的一个真实缺陷 —— 搜索"AT&T"会看到"AT&amp;T"。
 *
 * 由此可以推出组件层必须遵守的一条规则：
 * **`HighlightText.vue` 只能通过插值渲染，绝不能使用 HTML 注入指令。**
 * 一旦改用注入指令，这里返回的原始文本（未转义）就会变成 XSS 载体。
 * 这条约束写在组件里，也写在这里，因为它是两者共同成立的契约。
 */

/** 一段文本片段 */
export interface HighlightSegment {
  /** 原始文本（未转义，由渲染层负责转义） */
  text: string
  /** 是否为高亮片段 */
  highlight: boolean
}

/** 高亮标签（与后端约定一致，见后端 HighlightBuilder） */
const EM_OPEN = '<em>'
const EM_CLOSE = '</em>'

/**
 * 把带 <em> 标签的高亮片段切分为 [{ text, highlight }] 数组。
 *
 * 只把完全等于 `<em>` / `</em>` 的部分当作边界，其余一律视为普通文本 ——
 * 文档里若真的写了 "<em>"，它会被当成一个高亮起点（显示上无害，只是多了一段高亮）。
 * 这个取舍是有意的：宁可高亮范围判断偶尔出错，也不去解析任意 HTML。
 */
export function parseHighlight(raw: string): HighlightSegment[] {
  if (!raw) return []

  const segments: HighlightSegment[] = []
  // 用捕获组切分，保留分隔符本身
  const parts = raw.split(/(<\/?em>)/g)
  let highlight = false

  for (const part of parts) {
    if (part === '') continue
    if (part === EM_OPEN) {
      highlight = true
      continue
    }
    if (part === EM_CLOSE) {
      highlight = false
      continue
    }
    segments.push({ text: part, highlight })
  }

  return segments
}

/** HTML 特殊字符到实体的映射。 */
const ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/**
 * 把 HTML 特殊字符转成实体。
 *
 * **不要用它处理 `parseHighlight` 的返回值**（会双重转义，见文件头说明）。
 * 它用于另一类场景：需要把纯文本拼进一段 HTML 字符串时
 * （例如生成 `title` 属性、导出 CSV、或将来某个非 Vue 的渲染路径）。
 */
export function escapeHtml(input: string): string {
  return input.replace(/[&<>"']/g, (char) => ESCAPE_MAP[char] ?? char)
}

/**
 * 去掉高亮标签，返回纯文本（用于列表摘要、复制到剪贴板等场景）。
 */
export function stripHighlight(raw: string): string {
  if (!raw) return ''
  return raw.replace(/<\/?em>/g, '')
}

/**
 * 取出高亮片段中第一段纯文本，用作结果列表的摘要。
 */
export function firstFragmentText(fragments?: string[]): string {
  if (!fragments || fragments.length === 0) return ''
  return stripHighlight(fragments[0])
}
