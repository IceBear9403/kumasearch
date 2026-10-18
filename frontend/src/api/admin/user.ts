import { del, get, post, put } from '../request'

import type { PageQuery, PageResult } from '@/types/api'
import type { SysRole, SysUser } from '@/types/admin'

const BASE = '/admin/user'

/** 分页查询用户（后端只接受 pageNum / pageSize / keyword） */
export function listUsers(params?: PageQuery & { keyword?: string }): Promise<PageResult<SysUser>> {
  return get<PageResult<SysUser>>(BASE, params ? { ...params } : undefined)
}

/** 新建用户，返回新用户主键 */
export function createUser(data: SysUser): Promise<number> {
  return post<number>(BASE, data)
}

/** 更新用户：PUT 无路径变量，id 必填并放在请求体里 */
export function updateUser(data: SysUser): Promise<void> {
  return put<void>(BASE, data)
}

/** 删除用户（逻辑删除） */
export function removeUser(id: number): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/** 查询用户拥有的角色（后端返回角色对象数组，不是 ID 数组） */
export function getUserRoles(userId: number): Promise<SysRole[]> {
  return get<SysRole[]>(`${BASE}/${userId}/roles`)
}

/** 给用户分配角色（覆盖式：传完整角色 ID 列表，空数组表示清空） */
export function assignUserRoles(userId: number, roleIds: number[]): Promise<void> {
  return post<void>(`${BASE}/${userId}/roles`, roleIds)
}

/** 角色列表（/admin/role） */
export function listRoles(): Promise<SysRole[]> {
  return get<SysRole[]>('/admin/role')
}
