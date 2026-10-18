import { del, get, post, put } from './request'

import type { PageQuery, PageResult } from '@/types/api'
import type { ContentTypeSchema, SearchContent } from '@/types/admin'

/**
 * 前台投稿接口（`/api/content/**`）。
 *
 * <h3>与 `api/admin/content.ts` 的区别不是"换个路径"，而是两套契约</h3>
 * 两条路写的是同一张表，但：
 *  - 这里面对**任何登录用户**（需权限码 `content:submit`），管理侧面对操作者；
 *  - 因此 `status` / `authorId` / `bizWeight` 由服务端接管，请求里带了也不作数；
 *  - 撤回只能撤自己的、且未发布的稿子，服务端按令牌里的用户校验归属。
 *
 * <h3>提交成功 ≠ 能搜到</h3>
 * 投稿一律落"待审"（`status=0`），要管理员在后台放行后才进检索结果。
 * 接口返回 200 与一个有效 id 只代表**已入库**，不代表已发布 ——
 * 提示语不要写成"发布成功"，那会让人以为可以立刻搜到。
 */

/**
 * 可投稿的类型及其字段结构。
 *
 * 只返回**已启用且允许前台投稿**（`allow_user_submit=1`）的类型，
 * 因此返回空数组意味着"管理员还没有开放任何类型的投稿"，
 * 这是一个正常状态而不是接口出错 —— 投稿页要给出对应的说明文案。
 *
 * 返回里的 `fields[].inExtra` 决定字段落点、`serverManagedFields` 决定哪些不渲染，
 * 都按后端给的值走，前端不要另维护名单（见 `types/admin.ts` 的说明）。
 */
export function listSubmittableSchemas(): Promise<ContentTypeSchema[]> {
  return get<ContentTypeSchema[]>('/content/schema')
}

/**
 * 我的投稿（分页）。
 *
 * `typeCode` 可省略（返回全部类型），`authorId` **不接受传参** ——
 * 服务端强制取当前登录用户，这是这个接口唯一有意义的口径。
 *
 * 结果含全部状态，所以列表里会同时出现已发布与待审的稿子；
 * 被驳回的稿子，原因在 `extra.rejectReason`（JSON 字符串，要 parse）。
 */
export function mySubmissions(
  params?: PageQuery & { typeCode?: string },
): Promise<PageResult<SearchContent>> {
  return get<PageResult<SearchContent>>('/content/my', params ? { ...params } : undefined)
}

/**
 * 新增投稿。
 *
 * `bizId` **不必填也不要填**：服务端会生成 `s-<32位十六进制>` 并覆盖请求里的值。
 * 要记住这个返回值对应的 bizId，得回列表查（响应只给主键 id）。
 *
 * 受限流约束：同一用户每小时 10 次、同一 IP 每小时 60 次，超限返回 `10003`。
 *
 * @returns 记录主键（不是可检索状态；稿件此时是待审）
 */
export function submitContent(data: SearchContent): Promise<number> {
  return post<number>('/content/submit', data)
}

/**
 * 修改自己的投稿。
 *
 * 三条服务端约束（前端拦不住，也别试图在前端"模拟"出同样的判断）：
 *  - 只能改自己的 —— 改别人的返回 `43001`，**不是 403**；
 *  - 只能改**未发布**的（待审 / 已驳回）—— 已发布的返回 `20006`，要联系管理员先下架；
 *  - 改完回到待审状态（改过的是新的一版，要重新审）。
 *
 * 同样受限流约束（与新增共用同一个配额桶）。
 *
 * ⚠️ 后端返回 `void` 而不是新 id —— 更新是幂等的 upsert，`typeCode + bizId` 不变。
 */
export function updateSubmission(data: SearchContent): Promise<void> {
  return put<void>('/content/submit', data)
}

/**
 * 撤回自己的投稿。
 *
 * 逻辑删除 MySQL 行 + 异步删 ES 文档，约 1 秒后不再可搜。
 * 与修改受同一条约束：已发布的稿子不能自行撤回（`20006`）。
 *
 * **这条接口不限流**（撤回是"减少"动作，反复撤自己的稿不构成滥用）——
 * 所以不要把它的失败当成限流来提示。
 */
export function withdrawSubmission(typeCode: string, bizId: string): Promise<void> {
  return del<void>(`/content/my/${typeCode}/${bizId}`)
}
