import { del, get, post } from './request'

import type {
  SearchRequest,
  SearchResponse,
  SearchTypeDTO,
  SuggestDTO,
  WebSearchRequest,
  WebSearchResponse,
} from '@/types/search'

/**
 * 站内检索。**匿名可用**（不需要登录）。
 *
 * 未登录时后端拿不到 userId，只影响搜索历史与日志，不影响结果。
 */
export function search(data: SearchRequest): Promise<SearchResponse> {
  return post<SearchResponse>('/search', data)
}

/**
 * 网页采集检索。**匿名可用**，与站内检索是独立的两个接口。
 *
 * 结果页并发请求两者、分区渲染，因此调用方要各自 `catch`：
 * 任一侧失败应只让那一块显示"加载失败，点击重试"，
 * 而不是让整个结果页一片空白。
 *
 * ⚠️ 只返回**已通过人工审核**（status=1）的采集内容 —— 这是后端强制注入的过滤，
 * 调用方无法绕过，前端也不需要传。
 */
export function webSearch(data: WebSearchRequest): Promise<WebSearchResponse> {
  return post<WebSearchResponse>('/search/web', data)
}

/**
 * 联想补全。
 *
 * 后端 `typeCode` 必填、`prefix` 可选（为空时仍应返回热门/历史建议），
 * 因此这里 prefix 声明为可选。
 */
export function suggest(params: {
  typeCode: string
  prefix?: string
  size?: number
}): Promise<SuggestDTO[]> {
  return get<SuggestDTO[]>('/search/suggest', { ...params })
}

/** 热词榜：后端 typeCode 必填 */
export function hotWords(params: { typeCode: string; size?: number }): Promise<string[]> {
  return get<string[]>('/search/hot', { ...params })
}

/**
 * 可检索类型列表：站内类型选择器的数据源。**匿名可用**。
 *
 * 只返回**启用中**的类型，且不含 `webpage`（采集内容有独立的 `/search/web` 入口，
 * 见 `types/search.ts` 的 `SearchTypeDTO`）。
 *
 * 返回空数组是合法状态（后端还没配置任何类型），调用方要按"暂无可选类型"处理，
 * 而不是当成接口故障 —— 空数组不代表这次调用失败。
 */
export function searchTypes(): Promise<SearchTypeDTO[]> {
  return get<SearchTypeDTO[]>('/search/types')
}

/**
 * 搜索历史：后端 typeCode 必填。
 *
 * **该接口需登录**（未登录返回 20001），调用前必须确认已有登录态。
 * 未登录用户的历史在浏览器本地，走 `utils/localHistory.ts` ——
 * 不要为了"统一"而在未登录时也调它：那会触发一次 401，
 * 而拦截器会把 401 当成登录过期，直接跳登录页。
 */
export function searchHistory(params: { typeCode: string; size?: number }): Promise<string[]> {
  return get<string[]>('/search/history', { ...params })
}

/**
 * 清空搜索历史：后端 typeCode 必填。
 * 该接口需登录（未登录返回 20001）。
 */
export function clearSearchHistory(params: { typeCode: string }): Promise<void> {
  return del<void>('/search/history', { ...params })
}
