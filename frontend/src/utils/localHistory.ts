/**
 * 未登录状态下的搜索历史（浏览器本地）。
 *
 * # 为什么未登录的历史存在本地而不是服务端
 *
 * 搜索改成匿名可用之后，一个没登录的用户也可能反复搜索。把匿名搜索历史写到服务端
 * 意味着要么凭空造一个匿名设备 ID（那实质上就是一枚没有告知用户的追踪 Cookie），
 * 要么把所有人的匿名历史混在一个桶里（那它就不是"我的历史"了）。
 * 存在浏览器本地是唯一与"用户没登录"这一事实相符的做法：
 * **数据留在用户自己的设备上，服务端从头到尾不知道他搜过什么。**
 *
 * # 登录之后不自动合并
 *
 * 本地历史是**匿名行为**的记录，登录后的账号是实名的。自动合并等于把两者
 * 静默关联起来，而用户对"没登录时搜的东西"有合理的不同预期 ——
 * 他当时的预期很可能正是"这不会留在我的账号上"。
 *
 * 因此导入必须由用户显式发起：登录成功后弹一次
 * 「是否导入本地搜索记录？（导入后仅你可见）」，选是才调
 * `POST /api/auth/history/import`，再由调用方决定是否 clear()。
 * **这个决定属于用户，不属于实现。**
 *
 * # 存储形态
 *
 * 用 JSON 数组而不是拼接字符串：去重与截断都是几行代码，且不必担心分隔符
 * 出现在关键词里（中文搜索词里出现任何标点都是完全正常的）。
 */

/** localStorage 键。前缀与令牌键（`kuma_access_token`）保持一致，便于按前缀清理。 */
const STORAGE_KEY = 'kuma_search_history'

/** 最多保留多少条。与后端 `kuma:hist:{userId}:{type}` 的 50 条上限一致。 */
export const MAX_ITEMS = 50

/** 单条本地历史 */
export interface LocalHistoryItem {
  /** 关键词 */
  k: string
  /** typeCode：历史按类型分开，因为搜索结果本身就分类型 */
  tc: string
  /** epoch 毫秒。用于排序 —— 数组顺序会被其它操作打乱，时间戳不会 */
  t: number
}

/**
 * 读取全部本地历史（按时间倒序）。
 *
 * 解析失败一律返回空数组而不是抛异常：localStorage 里的内容是用户可控的
 * （他在控制台里改一个字符就能让它不是合法 JSON），而"历史读不出来"
 * 绝不该让整个搜索页崩掉 —— 它只是一个便利功能。
 */
export function list(): LocalHistoryItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter(
        (item): item is LocalHistoryItem =>
          !!item &&
          typeof item === 'object' &&
          typeof (item as LocalHistoryItem).k === 'string' &&
          typeof (item as LocalHistoryItem).tc === 'string',
      )
      .map((item) => ({
        k: item.k,
        tc: item.tc,
        // 时间戳缺失时给 0：排序上排到最后，但它仍然可读、可导入
        t: typeof item.t === 'number' ? item.t : 0,
      }))
      .sort((a, b) => b.t - a.t)
  } catch {
    return []
  }
}

/**
 * 记录一次搜索。
 *
 * 同一个关键词（同 typeCode）只保留一条，并把它的时间更新为当前 ——
 * 这就是"最近搜过的在最前"应有的语义，不需要额外去重逻辑。
 *
 * @returns 更新后的完整列表，方便调用方直接渲染
 */
export function push(keyword: string, typeCode: string): LocalHistoryItem[] {
  const trimmed = keyword.trim()
  if (!trimmed || !typeCode) {
    return list()
  }

  const rest = list().filter((item) => !(item.k === trimmed && item.tc === typeCode))
  const next = [{ k: trimmed, tc: typeCode, t: Date.now() }, ...rest].slice(0, MAX_ITEMS)
  write(next)
  return next
}

/** 某个类型下的历史关键词（按时间倒序）。 */
export function keywordsOf(typeCode: string): string[] {
  return list()
    .filter((item) => item.tc === typeCode)
    .map((item) => item.k)
}

/**
 * 全部历史关键词（按时间倒序，跨类型去重）。
 *
 * 用于「还没选检索类型」的场景 —— 搜索首页在用户选定类型之前，
 * 也是要给他看"我上次搜过什么"的。此时按类型过滤会让列表空着，
 * 而用户并不知道自己是因为"没选类型"才看不到。
 *
 * 去重保留**最先出现的那一条**：list() 已按时间倒序，因此留下的是最近搜过的那个类型，
 * 对用户来说"最近搜过"比"哪个类型"重要得多。
 */
export function allKeywords(): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const item of list()) {
    if (seen.has(item.k)) continue
    seen.add(item.k)
    result.push(item.k)
  }
  return result
}

/** 清空某个类型下的历史；不传类型则清空全部。 */
export function clear(typeCode?: string): LocalHistoryItem[] {
  const next = typeCode ? list().filter((item) => item.tc !== typeCode) : []
  write(next)
  return next
}

/** 是否还有可导入的内容。用于决定登录后要不要弹导入提示。 */
export function hasAny(): boolean {
  return list().length > 0
}

/**
 * 写入。
 *
 * 单独一个函数而不是让每个调用方自己 `setItem`：写失败（Safari 无痕模式、
 * 配额满）必须集中处理一次。**失败只记 console，不抛**——
 * 存不下历史是一个可以用、但不记住的功能，不该阻断搜索。
 */
function write(items: LocalHistoryItem[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch (e) {
    console.warn('[localHistory] 写入本地搜索历史失败，本次记录将被丢弃', e)
  }
}
