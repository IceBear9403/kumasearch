/**
 * 通用格式化工具
 */

function pad(num: number): string {
  return num < 10 ? `0${num}` : String(num)
}

/** 格式化日期时间，默认格式 YYYY-MM-DD HH:mm:ss */
export function formatDateTime(value?: string | number | Date | null): string {
  if (value === null || value === undefined || value === '') return '-'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** 格式化日期，格式 YYYY-MM-DD */
export function formatDate(value?: string | number | Date | null): string {
  if (value === null || value === undefined || value === '') return '-'
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 千分位格式化数字 */
export function formatNumber(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return value.toLocaleString('zh-CN')
}

/**
 * 格式化相关度评分，如 12.34。
 *
 * <p><b>为什么 score 可能是 null/undefined：</b>ES 只在「查询参与打分」时返回 {@code _score}。
 * 按字段排序（本站检索的排序链尾部总会追加一个主键兜底排序）且没有开启 {@code trackScores}
 * 时，{@code _score} 就是 null；后端又配置了「序列化时忽略 null」，
 * 于是 JSON 里连这个键都没有。直接 {@code hit.score.toFixed(2)} 会在渲染时抛
 * TypeError，而 Vue 的渲染错误会中止整棵子树的 patch —— 页面看起来就是"卡住了"。
 *
 * <p>所以展示评分的地方一律走这个函数，不要直接调 {@code toFixed}。
 */
export function formatScore(score?: number | null): string {
  if (score === null || score === undefined || Number.isNaN(score)) return '-'
  return score.toFixed(2)
}

/** 格式化检索耗时，如 12.3 ms */
export function formatTook(millis?: number | null): string {
  if (millis === null || millis === undefined || Number.isNaN(millis)) return '-'
  return `${millis.toFixed(1)} ms`
}

/** 格式化字节数 */
export function formatBytes(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '-'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(2)} ${units[index]}`
}

/** 截断文本并追加省略号 */
export function truncate(text: string, maxLength = 80): string {
  if (!text) return ''
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text
}
