/**
 * 后台管理相关类型
 *
 * 所有字段名严格对齐后端实体（`com.kuma.search.repository.entity.*`）
 * 与 `docs/04-api-design.md`，不做改名或增加后端不返回的字段。
 */

import type { PageQuery } from '@/types/api'

/* ------------------------------------------------------------------ */
/* 索引类型配置（t_index_type_config）                                 */
/* ------------------------------------------------------------------ */

/**
 * 字段类型。取值必须与后端枚举 `com.kuma.search.es.mapping.FieldType` **逐字一致**
 * （后端 `FieldType.fromCode` 按枚举名或 esType 匹配，且忽略大小写，但存进去的应当是枚举名）。
 *
 * ⚠️ 这里曾经写成小写的 `'text' | ... | 'float' | 'text_array' | 'keyword_array'` ——
 * 后端**没有** float / text_array / keyword_array 这三种，却多了 `NESTED` 与 `GEO_POINT`。
 * 改动前请先看 `FieldType.java`：多一个值不是前端能自己决定的，
 * 它意味着 MappingGenerator 里多一个分支。
 */
export type FieldType =
  | 'TEXT'
  | 'KEYWORD'
  | 'INTEGER'
  | 'LONG'
  | 'DOUBLE'
  | 'BOOLEAN'
  | 'DATE'
  | 'NESTED'
  | 'GEO_POINT'

/**
 * 单个字段的配置（`field_config` JSON 数组里的一个元素）。
 *
 * ⚠️ **属性名必须与后端 `com.kuma.search.es.mapping.FieldConfig` 逐字对应**，
 * 改这里等于改「后端能接受的 JSON」。这里曾经整体对不上（`field` vs `name`、
 * `weight` vs `boost`、`nested: true` vs `type: 'NESTED'`），
 * 而 `sql/data.sql` 里的真实配置用的是 `name` / `boost` / `pinyin` / `suggestWeight` ——
 * 照旧的类型写出来的配置，后端一条都收不下。
 */
export interface FieldConfig {
  /** 字段名。ES 侧与 `t_search_content` 的列名或 `extra` 里的 key 对应 */
  name: string
  /** 展示名，用于后台配置界面与 facet 面板标题 */
  label?: string
  type: FieldType
  /** 索引期分词器。仅 TEXT 有意义，其余类型配了会被后端拒绝 */
  analyzer?: string
  /** 查询期分词器。仅 TEXT 有意义 */
  searchAnalyzer?: string
  /** 查询期权重，参与 multi_match 字段加权。典型：title=3.0、summary=1.5、content=1.0 */
  boost?: number
  /** 是否参与默认全文检索 */
  searchable?: boolean
  /** 是否可作为聚合字段（facet） */
  facetable?: boolean
  /** 是否可排序 */
  sortable?: boolean
  /** 是否参与关键词高亮 */
  highlight?: boolean
  /** 是否生成拼音子字段（全拼 + 首字母）。仅 TEXT / KEYWORD 有意义 */
  pinyin?: boolean
  /** 是否生成补全建议子字段（写入独立的 suggest 索引）。仅 TEXT / KEYWORD 有意义 */
  suggest?: boolean
  /** 建议词权重，用于 completion suggester 排序 */
  suggestWeight?: number
  /** 是否多值字段（数组），如 tags */
  multiValued?: boolean
  /** DATE 类型的格式串，为空时用后端默认（同时认带空格与带 T 两种写法） */
  format?: string
  /** custom analyzer 场景下的补充过滤链，一般不用填 */
  filters?: string[]
}

/** 索引类型配置 */
export interface IndexTypeConfig {
  id?: number
  /** 类型编码，全局唯一 */
  typeCode: string
  /** 类型显示名 */
  typeName: string
  /** 索引别名，检索端只认别名 */
  indexAlias: string
  /** 当前生效的物理索引版本号 */
  currentVersion?: number
  /** 主分片数，创建索引后不可改 */
  shards?: number
  /** 每分片副本数，可在线调整 */
  replicas?: number
  /** 业务主键在源表中的字段名 */
  pkField?: string
  /** 字段配置 JSON 字符串（注意是字符串，不是数组） */
  fieldConfig?: string
  /** 检索配置 JSON 字符串 */
  searchConfig?: string
  /** 状态：1 启用 / 0 停用 */
  status?: number
  /**
   * 是否允许前台用户向该类型投稿：1 允许 / 0 不允许。默认 0。
   *
   * 它属于「这个类型是什么」的一部分，而不只是一个检索参数：typeCode 同时决定了
   * 内容的字段结构、落在哪个索引、以及**谁能往里写**。所以投稿开关的粒度是类型，不是全局。
   */
  allowUserSubmit?: number
  createTime?: string
  updateTime?: string
  /** 逻辑删除标记：0 未删 / 1 已删 */
  deleted?: number
}

/**
 * 索引运行状态。
 * 后端返回的是 `Map<String, Object>`，键集由 core 实现决定，
 * 目前包含 typeCode / alias / currentVersion / physicalIndex / suggestIndex /
 * exists / suggestRequired / suggestExists / missingIndexes /
 * documentCount / sourceRowCount。
 *
 * ⚠️ `exists` **只是主索引**在不在。联想索引是另一个物理索引，
 * 因此"索引正常吗"要 `exists && (!suggestRequired || suggestExists)` 两个布尔一起看：
 * 主索引在、联想索引缺失是一种真实存在的半成品状态（历史上出现过），
 * 只看 `exists` 会把它显示成一切正常。
 */
export interface IndexStatus {
  typeCode?: string
  alias?: string
  currentVersion?: number
  physicalIndex?: string
  suggestIndex?: string
  /** 主索引是否存在 */
  exists?: boolean
  /** 配置要求有联想索引吗（有字段声明了 `suggest: true` 才有） */
  suggestRequired?: boolean
  /** 联想索引是否存在 */
  suggestExists?: boolean
  /** 按配置应当存在、但实际不在的索引名（空数组 = 齐了） */
  missingIndexes?: string[]
  documentCount?: number
  sourceRowCount?: number
}

/* ------------------------------------------------------------------ */
/* 内容表单结构（供动态表单渲染）                                       */
/* ------------------------------------------------------------------ */

/**
 * 单个字段的表单结构（`GET /admin/content/schema` 与 `GET /content/schema` 返回的元素）。
 *
 * 与 {@link FieldConfig} 的区别：那个是**配置**（要写进 field_config 的完整字段定义），
 * 这个是**渲染指令**（只挑表单用得上、且不含 analyzer / boost / filters 这些内部实现）。
 * 后端刻意不返回后者：它们对表单没有意义，返回了只会诱导前端去画一个不该出现的输入框。
 */
export interface ContentFieldSchema {
  /** 字段名。命中 `t_search_content` 的固定列就绑列，否则写进 extra */
  name: string
  /** 展示名（表单 label）；字段没配 label 时后端回落成字段名 */
  label?: string
  /** 字段类型，决定用哪个控件 */
  type: FieldType
  /** 是否多值（KEYWORD 多值时用多选控件，如 tags） */
  multiValued?: boolean
  /** DATE 的格式串；为空表示用后端默认（同时认带空格与带 T 两种写法） */
  format?: string
  /**
   * 该填到**请求体顶层**（false，命中固定列）还是 **`extra` JSON 里**（true）。
   *
   * ⚠️ 判定由后端给，前端**不要**自己维护一份"哪些字段是固定列"的名单：
   * 两份名单一旦漂移，症状是"表单填的值静默丢失"——不报错、不入库、也搜不出。
   */
  inExtra?: boolean
}

/**
 * 一个可填内容的类型及其字段结构
 * （`GET /admin/content/schema` 与 `GET /content/schema` 返回的元素）。
 */
export interface ContentTypeSchema {
  typeCode: string
  typeName: string
  /**
   * 是否允许前台用户投稿。
   *
   * ⚠️ 这里是 **boolean**，而 {@link IndexTypeConfig} 上同名的那个字段是 **number（0/1）**——
   * 因为前者是表单结构的 DTO（后端 `ContentTypeSchemaDTO` 声明为 `boolean`），
   * 后者是直接映射 `t_index_type_config.allow_user_submit`（TINYINT）的实体。
   * 两个接口、两种形状，`if (schema.allowUserSubmit)` 与 `=== 1` 谁都对不上谁，
   * 按接口来源取用。
   */
  allowUserSubmit: boolean
  /** 可编辑字段清单（已排除 id / bizId / typeCode 这三个保留字段） */
  fields: ContentFieldSchema[]
  /**
   * 由**服务端接管**、表单不该渲染的字段名（status / authorId / bizWeight）。
   * 投稿页必须照它过滤（渲染了也会被后端覆盖，等于骗用户填了个没用的框）；
   * 后台内容页不受此限制 —— 那里的操作者本来就有权设状态、调权重。
   */
  serverManagedFields: string[]
}

/* ------------------------------------------------------------------ */
/* 内容管理（t_search_content）                                        */
/* ------------------------------------------------------------------ */

/**
 * 内容状态。
 *
 * ⚠️ 与 `t_index_type_config.status`（1 启用 / 0 停用）**不是一套语义**，别互相套用：
 * 这里是内容在审核流转里的位置，而 0 在那边意味着"这个类型整个停用了"。
 *
 * 三态的由来：投稿（UGC）与采集内容共用一张表，两者都要人工放行。
 *
 * - `0` 待审 —— 已入库，**也在 ES 里**，但检索期被强制加上 `status=1` 过滤，因此搜不到。
 *   不要以为"没进 ES 所以搜不到"：判断依据是查询期的门，不是写入期的门。
 * - `1` 已发布 —— 唯一可被搜到的状态。
 * - `2` 已驳回 —— 同样在库里、不在检索结果里；驳回原因写在 `extra.rejectReason`。
 */
export type ContentStatus = 0 | 1 | 2

/** 内容记录 */
export interface SearchContent {
  id?: number
  typeCode: string
  /** 业务系统原始主键，ES 文档 _id 由「类型 + 该值」派生。前缀可读出录入方：`m-` 后台、`s-` 投稿、采集侧是 url 哈希 */
  bizId: string
  title?: string
  content?: string
  tags?: string
  categoryId?: string
  /** 作者。投稿侧由服务端写死为当前用户 id，采集侧是 `system` 之类的标记 */
  authorId?: string
  price?: number
  /** 业务权重，值越大越靠前 */
  bizWeight?: number
  /**
   * 内容状态，见 {@link ContentStatus}。
   *
   * 投稿侧由服务端接管（新建一律 0、命中敏感词直接 2），只有后台才可以手工改它 ——
   * 「放行」就是把它改成 1 再保存。
   */
  status?: ContentStatus
  publishTime?: string
  /**
   * 类型私有字段 JSON 字符串（不是对象）。
   *
   * ⚠️ 它同时承载两类东西，要分清：
   *  - 在字段配置里**声明过**的键 —— 会被平铺进 ES 文档，是可检索的业务字段；
   *  - 声明过之外的键（如 `rejectReason` / `matchedWords`）—— 只在 MySQL 里留痕，
   *    **不会进索引**。因此不要靠 extra 里的键去搜内容。
   */
  extra?: string
  createTime?: string
  updateTime?: string
  deleted?: number
}

/* ------------------------------------------------------------------ */
/* 同步任务（t_sync_task）                                             */
/* ------------------------------------------------------------------ */

/** 任务类型：INCREMENT 增量 / FULL 全量 / RECONCILE 对账 */
export type SyncTaskType = 'INCREMENT' | 'FULL' | 'RECONCILE'

/** 同步任务 */
export interface SyncTask {
  id?: number
  /** 任务名称 */
  taskName: string
  /** 目标索引类型编码 */
  typeCode?: string
  taskType?: SyncTaskType
  /** 增量水位：上次同步到的业务时间 */
  lastSyncTime?: string
  /** 增量水位：上次同步到的业务主键 */
  lastSyncId?: number
  /** 调度 cron 表达式，为空表示仅手工触发 */
  cronExpr?: string
  /** 单批处理条数 */
  batchSize?: number
  /** 状态：1 启用 / 0 停用 */
  status?: number
  /** 上次实际执行时间 */
  lastRunTime?: string
  /** 上次执行结果摘要 */
  lastResult?: string
  createTime?: string
  updateTime?: string
}

/* ------------------------------------------------------------------ */
/* 用户与角色（t_sys_user / t_sys_role）                               */
/* ------------------------------------------------------------------ */

/** 用户状态：0 禁用 / 1 启用 */
export type UserStatus = 0 | 1

/** 系统用户 */
export interface SysUser {
  id?: number
  username: string
  /** 明文密码，仅新增时提交；后端任何情况下都不返回该字段 */
  password?: string
  nickname?: string
  email?: string
  status?: UserStatus
  createTime?: string
  updateTime?: string
  /** 逻辑删除标记 */
  deleted?: number
}

/** 系统角色 */
export interface SysRole {
  id: number
  roleCode: string
  roleName: string
  createTime?: string
  updateTime?: string
}

/* ------------------------------------------------------------------ */
/* 数据字典 / 词典（t_search_dict）                                    */
/* ------------------------------------------------------------------ */

/**
 * 词典类型：MAIN 主词 / STOP 停用词 / SYNONYM 同义词 / SENSITIVE 敏感词。
 *
 * SENSITIVE 是采集内容上线时新增的取值，复用同一张表而不是另建词库表 ——
 * 与「新增内容类型只需 INSERT 一行」是同一套思路。
 *
 * ⚠️ 但它与另外三种有一处本质区别，维护时要知道：
 * MAIN / STOP / SYNONYM 影响的是**排在前面的是谁**，改错了只是排得不好；
 * SENSITIVE 决定的是**哪条内容对外可见**，误加一个词会让一批正常的采集内容
 * 被直接驳回（`status=2`）。**往这个类型里加词前必须先想清楚它在正常语境里
 * 会不会出现**（例如"赌博"两字出现在一篇介绍反赌博的新闻里是完全正常的）。
 */
export type DictType = 'MAIN' | 'STOP' | 'SYNONYM' | 'SENSITIVE'

/** 词典项 */
export interface SearchDict {
  id?: number
  dictType: string
  /** 词条本身 */
  word: string
  /** 同义词列表（仅 SYNONYM 使用，多个以分隔符拼接） */
  synonyms?: string
  /** 状态：1 启用 / 0 停用 */
  status?: number
  updateTime?: string
}

/* ------------------------------------------------------------------ */
/* 采集：种子站与人工审核（t_crawl_seed / t_crawl_audit_log）          */
/*                                                                     */
/* 采集到的网页本身不作为独立内容体系 —— 它进 t_search_content，        */
/* type_code='webpage'，因此审核队列里的一行就是一条 SearchContent。    */
/* ------------------------------------------------------------------ */

/** 种子站启用状态：1 启用 / 0 停用 */
export type CrawlSeedStatus = 0 | 1

/**
 * 采集种子站（t_crawl_seed）。
 *
 * ⚠️ 这张表是「可以对外发起真实请求」的配置入口，因此后端把它做成了两步：
 * **新建时一律落为停用**（`status=0`），要采必须再显式启用一次。
 * 前端不要"顺手"帮用户把新建的站启用 —— 那会让「加一个站」与「开始采这个站」
 * 重新合并成一个动作，而后者的后果（对方站点的流量、审核队列的量）是外部的。
 */
export interface CrawlSeed {
  id?: number
  /** 种子站名称，仅用于后台展示 */
  seedName: string
  /** 入口 URL，采集从这里出发 */
  entryUrl: string
  /** 站点域名，只抓该域；是否含子域由 allowSubdomain 决定 */
  domain: string
  /**
   * 是否允许采集子域名：1 允许 / 0 不允许。
   *
   * 默认 0。放开会把范围从一个站变成一个机构的全部站
   * （`www` / `shop` / `bbs` 是完全不同的内容源），误采的代价是审核队列爆炸。
   */
  allowSubdomain?: number
  /** 路径前缀，把范围限死在栏目内（如 `/news/`）；为空表示全站 */
  pathPrefix?: string
  /** 距种子的最大层级 */
  maxDepth?: number
  /** 单种子累计页数上限。★ 不能省，一次误配就足以把百万页站点整个搬进来 */
  maxPages?: number
  /** 对本站的请求速率上限（每秒）。0.5 表示 2 秒一个请求 */
  qps?: number
  /** 是否遵守 robots.txt：1 遵守 / 0 不遵守。置 0 需要留下明确理由（如已获站点授权） */
  respectRobots?: number
  /** 1 启用 / 0 停用 */
  status?: CrawlSeedStatus
  createTime?: string
  updateTime?: string
  deleted?: number
}

/**
 * 队列概览的一行。
 *
 * 后端返回的是 `Map<String, Object>`，键集由服务层决定，因此这里全部可选。
 * 抽成一个具名类型而不是用 `Record<string, unknown>`，
 * 是为了让读取处有一个集中的地方可以看到"后端到底给了哪些键"。
 */
export interface CrawlQueueOverview {
  /** 种子站主键 */
  seedId?: number
  seedName?: string
  domain?: string
  /** 待抓 */
  pendingCount?: number
  /** 抓取中 */
  fetchingCount?: number
  /** 已抓 */
  doneCount?: number
  /** 失败待重试 */
  failedCount?: number
  /** 死信（重试次数用尽） */
  deadCount?: number
  /** 被 robots 拒绝 */
  blockedCount?: number
  /** 页数配额是否已用尽 */
  quotaExhausted?: boolean
}

/** 审核动作：放行 / 驳回 / 撤回下架 */
export type CrawlAuditAction = 'APPROVE' | 'REJECT' | 'TAKEDOWN'

/**
 * 审核留痕（t_crawl_audit_log）。只追加，不更新不删除。
 *
 * `REJECT` 与 `TAKEDOWN` **不能合并**：前者拦在入库之前，后者需要额外触发一次 ES 删除。
 * 混成一个状态就会丢掉「这条内容是否曾经对外可见过」这个关键事实 ——
 * 而它在投诉与法务场景下恰恰是唯一要回答的问题。
 */
export interface CrawlAuditLog {
  id?: number
  /** 归一化 URL 的 MD5，与 t_crawl_url.url_hash 同源 */
  urlHash?: string
  /** 指向 t_search_content.id。内容被物理清理后会悬空，此时靠 urlHash 追溯 */
  contentId?: number
  action?: CrawlAuditAction
  /** 操作者用户名；自动规则产生的记录是 `system` */
  operator?: string
  /** 人工填写的理由；自动驳回时写触发规则 */
  reason?: string
  /** 本次命中的敏感词，逗号分隔 */
  matchedWords?: string
  createTime?: string
}

/**
 * 审核列表的筛选项。
 *
 * `status` 只允许 0（待审）与 2（已驳回）+ 不传（两者都要）：
 * **已发布（1）的内容不在这条队列里**，它属于「内容管理」。
 * 把它也列进来会让"还有多少等着处理"这个问题失去答案。
 */
export interface CrawlAuditQuery extends PageQuery {
  status?: 0 | 2
  /** 按来源域名筛选 */
  domain?: string
  /** 按命中的敏感词筛选 */
  matchedWords?: string
}

/**
 * 批量审核的动作。
 *
 * **刻意没有 `TAKEDOWN`。** 撤回是物理移除、不可逆，且作用于已经对外可见过的内容 ——
 * 一次误操作的代价与"少点几下鼠标"完全不成比例，所以它始终逐条进行。
 * 后端也据此拒绝：批量接口只认 `APPROVE` / `REJECT`。
 */
export type CrawlBatchAction = 'APPROVE' | 'REJECT'

/**
 * 批量审核的请求体。
 *
 * 两种定位方式**二选一**：`contentIds` 用于「处理勾中的这几条」，
 * `domain` / `matchedWords` 用于「处理筛选条件下的全部待审」。
 * 两者同时传时以后端**按 id 处理**为准（不报错，但前端不该依赖这个宽容）。
 */
export interface CrawlBatchAuditRequest {
  action: CrawlBatchAction
  /** 处理勾中的这几条。最多 2000 条，超出后端报参数错误（不静默截断） */
  contentIds?: number[]
  /** 按来源域名处理（与列表筛选同一个口径，传完整域名） */
  domain?: string
  /** 按命中的敏感词处理 */
  matchedWords?: string
  /** 按条件处理时的条数上限，省略取 200，最大 2000 */
  limit?: number
  /** 审核理由，会写进本批**每一条**的审核日志，措辞应能覆盖整批 */
  reason?: string
}

/** 单条失败明细 */
export interface CrawlBatchFailure {
  contentId?: number
  /** 中文短句，可直接展示给人看 */
  reason?: string
}

/**
 * 批量审核结果。
 *
 * 四个计数满足 `requested = succeeded + failed + skipped`，这是唯一的对账契约。
 *
 * **`failed` 与 `skipped` 必须分开呈现**，因为处置方式相反：
 * `failed` 需要重试或排查，`skipped` 重试没有意义（状态已经是目标状态，
 * 或这条内容不属于采集内容）—— 把两者合成一句"有 N 条没处理成功"，
 * 会让运维去重试一批本来就没问题的数据。
 */
export interface CrawlBatchAuditResult {
  requested?: number
  succeeded?: number
  failed?: number
  skipped?: number
  failures?: CrawlBatchFailure[]
  /** 处理完后系统里还剩多少条**待审**（全部，不只是本次筛选条件下的）。不为 0 就再批一次 */
  remainingPending?: number
}
