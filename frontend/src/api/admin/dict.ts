import { del, get, post } from '../request'

import type { SearchDict } from '@/types/admin'

const BASE = '/admin/dict'

/** 词典列表，可按词典类型（dictType）过滤；为空时返回全部 */
export function listDicts(params?: { dictType?: string }): Promise<SearchDict[]> {
  return get<SearchDict[]>(BASE, params ? { ...params } : undefined)
}

/** 新增或更新词典（以 id 是否为空区分），返回记录主键 */
export function createDict(data: SearchDict): Promise<number> {
  return post<number>(BASE, data)
}

/** 删除词典项（按数字主键 id） */
export function removeDict(id: number): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}
