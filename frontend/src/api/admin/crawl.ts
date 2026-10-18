import { del, get, post, put } from '../request'

import type {
  CrawlAuditLog,
  CrawlAuditQuery,
  CrawlBatchAuditRequest,
  CrawlBatchAuditResult,
  CrawlQueueOverview,
  CrawlSeed,
  SearchContent,
} from '@/types/admin'
import type { PageQuery, PageResult } from '@/types/api'

/* ------------------------------------------------------------------ */
/* 人工审核（/api/admin/crawl/audit）                                  */
/*                                                                     */
/* 权限码复用 admin:content:manage，不新增。                           */
/* ------------------------------------------------------------------ */

const AUDIT_BASE = '/admin/crawl/audit'

/**
 * 待审 / 已驳回列表。
 *
 * 返回的就是 `SearchContent` —— 采集内容不是独立的内容体系，
 * 它就是 `type_code='webpage'` 的内容行。因此这里的字段与「内容管理」是同一套。
 */
export function listAuditContents(
  params: CrawlAuditQuery,
): Promise<PageResult<SearchContent>> {
  return get<PageResult<SearchContent>>(AUDIT_BASE, { ...params })
}

/** 待审数量，供后台角标。 */
export function countPendingAudit(): Promise<number> {
  return get<number>(`${AUDIT_BASE}/pending-count`)
}

/**
 * 放行：置为已发布，走既有写链路进 ES。
 *
 * ⚠️ **写入是异步的**：接口返回只代表状态已改、Outbox 事件已写出，
 * ES 侧是秒级异步可见。因此"点了放行但立刻搜不到"在几秒内是正常的，
 * 界面不要在这一刻去断言"已经可以搜到了"。
 */
export function approveContent(contentId: number, reason?: string): Promise<void> {
  return post<void>(`${AUDIT_BASE}/${contentId}/approve`, { reason })
}

/** 驳回：置为已驳回，不进 ES（已发布过的会同步从检索中消失）。 */
export function rejectContent(contentId: number, reason?: string): Promise<void> {
  return post<void>(`${AUDIT_BASE}/${contentId}/reject`, { reason })
}

/**
 * 撤回下架：从 ES 中移除。
 *
 * 三个动作里唯一作用于「已经对外可见过」的内容的，因此调用前必须二次确认，
 * 且提示语要说清后果（内容会从搜索结果里消失），而不是笼统的"确定要删除吗"。
 */
export function takedownContent(contentId: number, reason?: string): Promise<void> {
  return post<void>(`${AUDIT_BASE}/${contentId}/takedown`, { reason })
}

/**
 * 批量放行 / 驳回。
 *
 * 队列积压时用它，不要一条条点。两种定位方式二选一（见 `CrawlBatchAuditRequest`）。
 *
 * ⚠️ 三条要知道的语义，调用方**必须**把它们区分开呈现：
 *
 * 1. **返回值不是「成功/失败」**，而是 `requested` / `succeeded` / `failed` / `skipped`
 *    四个计数。批量操作的本质是**一定会部分成功**，把它压成一个布尔值，
 *    就会让调用方要么漏掉那几条、要么把已经成功的上百条重做一遍。
 * 2. **`skipped` 不是错误** —— 状态已经是目标状态，或这条不是采集内容。
 *    它与 `failed` 的处置方式相反，不要合并成一句"有 N 条没成功"。
 * 3. **`remainingPending` 才是"完了没有"的答案**：不为 0 就再调一次。
 *    单次有条数上限（`limit`，默认 200），线上积压上千条时就是靠反复调用清空的。
 *
 * 后端逐条各开一个事务，所以中途失败**不会回滚已经成功的部分**，
 * 重试是安全的（已成功的那几条会以 `skipped` 返回）。
 */
export function batchAudit(payload: CrawlBatchAuditRequest): Promise<CrawlBatchAuditResult> {
  return post<CrawlBatchAuditResult>(`${AUDIT_BASE}/batch`, { ...payload })
}

/**
 * 某条 URL 的审核历史。
 *
 * 按 `urlHash` 而不是 `contentId` 查：内容被撤回后 contentId 会查不到东西，
 * 而 urlHash 永远可重算。
 */
export function listAuditLogs(
  params: PageQuery & { urlHash?: string },
): Promise<PageResult<CrawlAuditLog>> {
  return get<PageResult<CrawlAuditLog>>(`${AUDIT_BASE}/logs`, { ...params })
}

/* ------------------------------------------------------------------ */
/* 种子站与队列（/api/admin/crawl/seed）                               */
/*                                                                     */
/* 权限码复用 admin:sync:manage，与「同步任务」相同。                  */
/* ⚠️ 这里的每个写操作都在放宽一个「对外发起真实请求」的能力。         */
/* ------------------------------------------------------------------ */

const SEED_BASE = '/admin/crawl/seed'

/** 列出全部种子站（含停用）。 */
export function listSeeds(): Promise<CrawlSeed[]> {
  return get<CrawlSeed[]>(SEED_BASE)
}

/**
 * 队列概览：每个种子站的待抓/已抓量与配额是否用尽。
 *
 * 与 `listSeeds` 分开一个接口：它要对每个种子站各跑两次 COUNT，
 * 打开列表页时不一定需要这组数字。
 */
export function seedQueueOverview(): Promise<CrawlQueueOverview[]> {
  return get<CrawlQueueOverview[]>(`${SEED_BASE}/queue`)
}

/** 新建种子站。**默认停用**，后端强制，前端不要替用户启用。 */
export function createSeed(seed: CrawlSeed): Promise<number> {
  return post<number>(SEED_BASE, { ...seed })
}

/**
 * 更新种子站。
 *
 * ⚠️ 要求提交整行：漏传的字段会被置空。因此调用前必须先取到完整对象再改，
 * 不要用 `{ id, 要改的字段 }` 这样的局部对象调用它 ——
 * 那会把 `entryUrl`、`domain` 直接清掉（服务层的校验会拦下这一种，
 * 但拦下之后用户看到的是"更新失败"，不知道是自己少传了字段）。
 */
export function updateSeed(seed: CrawlSeed): Promise<void> {
  return put<void>(SEED_BASE, { ...seed })
}

/**
 * 启用 / 停用种子站。
 *
 * 单独一个接口而不是复用 `updateSeed`：停采是运维最常用的动作，
 * 而 update 要求提交整行。让这个动作只需要一个状态值，能显著降低误操作面。
 */
export function changeSeedStatus(id: number, status: 0 | 1): Promise<void> {
  return post<void>(`${SEED_BASE}/${id}/status`, { status })
}

/**
 * 删除种子站。
 *
 * ⚠️ 不只是删一条配置：后端会把该站队列里**尚未抓取**的任务一并作废，
 * 否则它们会在站消失后继续被抓取入库。调用前必须二次确认并说清这一点。
 */
export function deleteSeed(id: number): Promise<void> {
  return del<void>(`${SEED_BASE}/${id}`)
}
