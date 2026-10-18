import { del, get, post, put } from '../request'

import type { SyncTask } from '@/types/admin'

const BASE = '/admin/sync-task'

/** 列出全部同步任务（后端返回数组，非分页，且不接受分页参数） */
export function listSyncTasks(): Promise<SyncTask[]> {
  return get<SyncTask[]>(BASE)
}

/** 新建同步任务，返回新任务主键 */
export function createSyncTask(data: SyncTask): Promise<number> {
  return post<number>(BASE, data)
}

/** 更新同步任务：PUT 无路径变量，id 必填并放在请求体里 */
export function updateSyncTask(data: SyncTask): Promise<void> {
  return put<void>(BASE, data)
}

/** 删除同步任务 */
export function removeSyncTask(id: number): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/** 手动触发同步任务 */
export function runSyncTask(id: number): Promise<void> {
  return post<void>(`${BASE}/${id}/run`)
}
