/**
 * 检索相关类型，字段与后端契约严格对齐
 */

/** 范围过滤条件 */
export interface RangeFilter {
  field: string
  gte?: number | string
  lte?: number | string
  gt?: number | string
  lt?: number | string
}

/** 聚合（facet）请求项 */
export interface FacetSpec {
  field: string
  size?: number
}

/** 排序项 */
export interface SortSpec {
  field: string
  order: 'asc' | 'desc'
  /** 排序模式，如 min / max / avg */
  mode?: string
}

/** 检索请求体 */
export interface SearchRequest {
  /** 检索类型编码（必填） */
  typeCode: string
  /** 关键词 */
  keyword?: string
  /** 检索字段 */
  searchFields?: string[]
  /** 精确过滤：field -> 取值列表 */
  filters?: Record<string, string[]>
  /** 范围过滤 */
  rangeFilters?: RangeFilter[]
  /** 需要聚合的字段 */
  facets?: FacetSpec[]
  /** 排序 */
  sorts?: SortSpec[]
  /** 需要高亮的字段 */
  highlightFields?: string[]
  /** 页码，从 1 开始 */
  pageNum?: number
  /** 每页数量 */
  pageSize?: number
  /** 游标（深分页） */
  cursor?: string
  /** 是否使用游标分页 */
  useCursor?: boolean
  /** 最小评分 */
  minScore?: number
  /** 是否模糊匹配 */
  fuzzy?: boolean
  /** 是否启用同义词 */
  synonym?: boolean
  /** 是否启用拼音检索 */
  pinyin?: boolean
  /** 聚合返回的桶数量上限 */
  aggSize?: number
}

/** 单条命中结果 */
export interface HitDTO {
  id: string
  /**
   * 相关度评分。
   *
   * ⚠️ **可能缺失**：ES 在按字段排序且未开启 `trackScores` 时不返回 `_score`，
   * 而后端配置了「序列化时忽略 null」，此时 JSON 里没有这个键。
   * 渲染时必须用 `formatScore()` 这类带空值处理的函数，不能直接 `score.toFixed(2)`
   * （那会在渲染阶段抛 TypeError，让整块结果不渲染 —— 表现为页面卡住）。
   */
  score?: number | null
  fields: Record<string, unknown>
  /** 字段名 -> 高亮片段数组，片段中带 <em> 标签 */
  highlights: Record<string, string[]>
  /** 摘要 */
  summary: string
}

/** 聚合桶。注意：后端返回的计数字段名是 docCount，不是 count */
export interface FacetBucket {
  key: string
  docCount: number
}

/** 聚合结果 */
export interface FacetDTO {
  field: string
  label: string
  total: number
  buckets: FacetBucket[]
}

/** 检索响应体 */
export interface SearchResponse {
  hits: HitDTO[]
  /** 深分页场景下可能为 null */
  total: number | null
  /** 游标翻页模式下为 null */
  pageNum: number | null
  pageSize: number
  /** total 为 null 时为 null */
  pages: number | null
  /** 游标分页时的下一页游标 */
  nextCursor: string | null
  /** 是否处于游标模式 */
  cursorMode: boolean
  facets: FacetDTO[]
  /** 耗时（毫秒） */
  tookMillis: number
  /** 是否部分结果 */
  partial: boolean
  /** 纠错后的关键词 */
  correctedKeyword?: string
}

/* ------------------------------------------------------------------ */
/* 网页采集检索（POST /search/web）                                     */
/*                                                                     */
/* 与站内检索是**两个接口、两种响应**，不是一个接口的两种形态。          */
/* 前端并发请求两者、分区渲染，因此任一侧失败都能独立降级：             */
/* 站内结果先渲染出来，网页区块显示"加载失败，点击重试"。               */
/* ------------------------------------------------------------------ */

/** 网页采集检索请求 */
export interface WebSearchRequest {
  keyword: string
  /** 站点白名单（最多 10 个）。留空表示不限站点 */
  domains?: string[]
  pageNum?: number
  /** 上限 50 */
  pageSize?: number
}

/** 网页采集检索的单条命中 */
export interface WebHit {
  id: string
  title: string
  /** 原文链接。详情一律跳原站，本站不落全文镜像 */
  sourceUrl: string
  domain: string
  /** 摘要（后端存的就是摘要，不是全文） */
  summary: string
  /** 字段名 -> 高亮片段。⚠️ 片段含 `<em>` 标签，必须用 v-html 之外的渲染方式 */
  highlights: Record<string, string[]>
  /** 相关度评分。可能缺失，理由与 `HitDTO.score` 相同，渲染前必须做空值处理 */
  score?: number | null
}

/** 网页采集检索响应 */
export interface WebSearchResponse {
  hits: WebHit[]
  total: number | null
  pageNum: number | null
  pageSize: number
  pages: number | null
  nextCursor: string | null
  cursorMode: boolean
  /** 来源站点聚合 */
  domainFacets: FacetBucket[]
  tookMillis: number
  partial: boolean
}

/** 联想来源：热词 / 索引 / 历史 */
export type SuggestSource = 'hot' | 'index' | 'history'

/** 联想结果项 */
export interface SuggestDTO {
  text: string
  weight: number
  source: SuggestSource
}

/**
 * 一个可检索类型，对应 `GET /api/search/types` 的返回项。
 *
 * 只有编码与名字两个字段 —— 后端刻意不下发字段映射与打分配置
 * （那些属于索引实现细节），前端也**不需要**它们：
 * 类型选择器的职责只是让用户在"文章 / 采集网页 / …"里选一个，然后把 typeCode 原样传回来。
 *
 * 注意这里**不含** `webpage`：采集内容走 `/api/search/web`，
 * 是结果页上与站内并列的另一个分组，不在站内类型选择器里出现。
 */
export interface SearchTypeDTO {
  /** 类型编码，检索时原样回传 */
  typeCode: string
  /** 类型展示名 */
  typeName: string
}
