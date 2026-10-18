import { del, get, post, put } from '../request'

import type { IndexStatus, IndexTypeConfig } from '@/types/admin'

const BASE = '/admin/index-type'

/** 列出全部类型配置（后端返回数组，非分页，且不接受 pageNum/keyword 参数） */
export function listIndexTypes(): Promise<IndexTypeConfig[]> {
  return get<IndexTypeConfig[]>(BASE)
}

/** 类型配置详情（按 typeCode） */
export function getIndexType(typeCode: string): Promise<IndexTypeConfig> {
  return get<IndexTypeConfig>(`${BASE}/${typeCode}`)
}

/** 新建类型配置，返回新配置主键 */
export function createIndexType(data: IndexTypeConfig): Promise<number> {
  return post<number>(BASE, data)
}

/**
 * 更新类型配置：PUT 无路径变量，标识放在请求体的 id 里。
 *
 * 请求体里**刻意没有** `typeCode` / `indexAlias` / `currentVersion`：
 * 它们是索引身份的一部分，服务端会无条件忽略（改了等于凭空换一份索引，
 * 旧索引与别名会变成孤儿，线上正在服务的检索瞬间失效）。
 * 用类型把"这几个字段发过去也没用"说出来，比在调用处写一行注释更可靠 ——
 * 注释会腐坏，类型不会。
 */
export function updateIndexType(
  data: Omit<IndexTypeConfig, 'typeCode' | 'indexAlias' | 'currentVersion'>,
): Promise<void> {
  return put<void>(BASE, data)
}

/** 删除类型配置：按数字主键 id，而非 typeCode（逻辑删除） */
export function removeIndexType(id: number): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/** 首次建索引 */
export function createIndex(typeCode: string): Promise<void> {
  return post<void>(`${BASE}/${typeCode}/create-index`)
}

/** 全量重建索引（蓝绿切换，耗时可能很长，不要做同步等待式超时） */
export function rebuildIndex(typeCode: string): Promise<void> {
  return post<void>(`${BASE}/${typeCode}/rebuild`)
}

/** 重建联想（completion）索引 */
export function rebuildSuggest(typeCode: string): Promise<void> {
  return post<void>(`${BASE}/${typeCode}/rebuild-suggest`)
}

/** 重载词典/分析器 */
export function reloadAnalyzers(typeCode: string): Promise<void> {
  return post<void>(`${BASE}/${typeCode}/reload-analyzers`)
}

/** 删除索引（危险操作，需另行重建才能恢复） */
export function deleteIndex(typeCode: string): Promise<void> {
  return del<void>(`${BASE}/${typeCode}/index`)
}

/** 预览将生成的 mapping，后端返回 mapping JSON 字符串 */
export function mappingPreview(typeCode: string): Promise<string> {
  return get<string>(`${BASE}/${typeCode}/mapping-preview`)
}

/** 查询索引运行状态（键集由 core 实现决定） */
export function indexStatus(typeCode: string): Promise<IndexStatus> {
  return get<IndexStatus>(`${BASE}/${typeCode}/status`)
}

/**
 * 校验 field_config JSON 合法性（只校验不落库）。
 *
 * 后端请求体是对象 `{ fieldConfig: "<JSON 字符串>" }`，返回 `R<Void>`；
 * 配置非法时抛业务异常，错误信息会定位到具体字段。
 */
export function validateFieldConfig(fieldConfig: string): Promise<void> {
  return post<void>(`${BASE}/validate-field-config`, { fieldConfig })
}
