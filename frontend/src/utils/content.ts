import type { ContentStatus } from '@/types/admin'

/**
 * 内容状态的展示口径。
 *
 * 后台内容维护页与「我的投稿」两处都要画这个标签，而它们的措辞必须一致 ——
 * 同一份稿子在后台显示"待审"、在用户那里显示"处理中"，会让人以为是两套流程。
 * 因此文案与配色只在这一处定义。
 */
export const CONTENT_STATUS_LABELS: Record<ContentStatus, string> = {
  0: '待审',
  1: '已发布',
  2: '已驳回',
}

/** 状态标签的配色。0 用 warning 而不是 info：待审是**需要有人去处理**的状态，不是一种中性态 */
export function contentStatusTagType(
  status?: ContentStatus,
): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 1:
      return 'success'
    case 0:
      return 'warning'
    case 2:
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 状态文案。未知/缺失时返回 `'-'` 而不是猜一个 ——
 * 把"后端没给"显示成"已发布"是会把人带偏的。
 */
export function contentStatusLabel(status?: ContentStatus): string {
  if (status === undefined || status === null) return '-'
  return CONTENT_STATUS_LABELS[status] ?? '-'
}

/**
 * 从内容的 `extra`（JSON 字符串）里取一个键。
 *
 * 投稿被驳回的原因（`rejectReason`）与命中的敏感词（`matchedWords`）就存在这里，
 * 而这两个键**没有**在字段配置里声明，因此不会进索引、只能从 `extra` 原文里读。
 * 解析失败一律当作"没有"：这是一条展示路径，不该因为一条脏数据把整个列表页打挂。
 */
export function readExtraKey(extra: string | undefined, key: string): string {
  if (!extra) return ''
  try {
    const parsed: unknown = JSON.parse(extra)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return ''
    const value = (parsed as Record<string, unknown>)[key]
    return value === null || value === undefined ? '' : String(value)
  } catch {
    return ''
  }
}
