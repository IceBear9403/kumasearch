import { del, get, post } from '../request'

import type { PageQuery, PageResult } from '@/types/api'
import type { ContentStatus, ContentTypeSchema, SearchContent } from '@/types/admin'

/**
 * 分页查询内容。
 *
 * `status` **不传表示全部状态**（含待审 0 与已驳回 2），而不是"只查已发布"——
 * 后台内容页正是要看到那些还没放行的稿子，默认只返回 status=1 会让待审队列
 * 在这个页面上不可见（它才是运营处理投稿的地方）。要只看已发布就显式传 `status: 1`。
 *
 * `authorId` 用于按作者筛选（例如查某个用户投了什么）。传字符串而不是数字：
 * `t_search_content.author_id` 是 VARCHAR，采集内容那里存的是 `system` 之类非数字值。
 */
export function listContent(
  params?: PageQuery & {
    typeCode?: string
    keyword?: string
    status?: ContentStatus
    authorId?: string
  },
): Promise<PageResult<SearchContent>> {
  return get<PageResult<SearchContent>>('/admin/content', params ? { ...params } : undefined)
}

/**
 * 取内容表单的字段结构，供动态表单渲染。
 *
 * ⚠️ 返回里的 `fields[].inExtra` 决定了每个字段该写进请求体顶层还是 `extra` JSON
 * —— 前端**不要**自己维护"哪些字段是固定列"的名单。两份名单一旦漂移，
 * 症状是"表单里填了值，保存后却搜不到"，而且不报任何错。
 *
 * 与前台 `POST /content/schema` 的区别：本接口返回**全部启用**的类型（不论是否允许投稿），
 * 因为后台操作者本来就该能录任何类型的内容；前台那个只返回 `allow_user_submit=1` 的。
 *
 * 一次返回全部而不是按 typeCode 取单条：内容页本来就要先有类型下拉框才能选类型，
 * 按类型再请求一次等于把同一份配置拿两遍。
 */
export function listContentSchemas(): Promise<ContentTypeSchema[]> {
  return get<ContentTypeSchema[]>('/admin/content/schema')
}

/** 内容详情（按 typeCode + bizId） */
export function getContent(typeCode: string, bizId: string): Promise<SearchContent> {
  return get<SearchContent>(`/admin/content/${typeCode}/${bizId}`)
}

/**
 * 新增或更新内容（幂等，以 typeCode + bizId 为准）。
 *
 * 后端只提供 upsert 语义的 POST，没有独立的 PUT 更新端点，
 * 因此新增与更新统一走本方法。
 *
 * @returns 记录主键
 */
export function createContent(data: SearchContent): Promise<number> {
  return post<number>('/admin/content', data)
}

/** 删除内容 */
export function removeContent(typeCode: string, bizId: string): Promise<void> {
  return del<void>(`/admin/content/${typeCode}/${bizId}`)
}

/** 批量导入内容，返回成功保存的条数 */
export function batchCreateContent(list: SearchContent[]): Promise<number> {
  return post<number>('/admin/content/batch', list)
}
