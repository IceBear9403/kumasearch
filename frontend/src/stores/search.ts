import { reactive, ref } from 'vue'
import { defineStore } from 'pinia'

import * as searchApi from '@/api/search'
import type { SearchRequest, SearchResponse } from '@/types/search'
import * as localHistory from '@/utils/localHistory'

/**
 * 检索状态：检索参数、结果、分页、facet 选中态
 */
export const useSearchStore = defineStore('search', () => {
  /* ----------------------------- state ----------------------------- */
  /** 当前检索参数 */
  const query = reactive<SearchRequest>({
    typeCode: '',
    keyword: '',
    pageNum: 1,
    pageSize: 10,
    filters: {},
    useCursor: false,
  })

  /** 检索结果 */
  const result = ref<SearchResponse | null>(null)
  /** 请求中 */
  const loading = ref(false)
  /** facet 选中态：field -> 已选中的 key 列表 */
  const selectedFacets = ref<Record<string, string[]>>({})
  /**
   * 历史关键词（用于回退）。
   *
   * 这个 ref 现在只是当前会话的**视图**，真正的存储在 localStorage ——
   * 改造前它是一份纯内存的 Pinia state，刷新即丢，于是"我上午搜过的词"
   * 下午就不见了。见 `utils/localHistory.ts`。
   *
   * ⚠️ 注意它**不是**服务端历史。登录用户的服务端历史走 `/search/history`，
   * 两者是两件事：本地的代表"这台设备上搜过什么"，服务端的代表"这个账号搜过什么"。
   */
  const keywordHistory = ref<string[]>([])

  /* ---------------------------- actions ---------------------------- */

  /** 设置检索类型 */
  function setTypeCode(typeCode: string): void {
    query.typeCode = typeCode
    query.pageNum = 1
  }

  /** 设置关键词 */
  function setKeyword(keyword: string): void {
    query.keyword = keyword
    query.pageNum = 1
  }

  /** 切换某个 facet 的选中项 */
  function toggleFacet(field: string, key: string): void {
    const current = selectedFacets.value[field] ?? []
    const next = current.includes(key)
      ? current.filter((item) => item !== key)
      : [...current, key]
    selectedFacets.value = { ...selectedFacets.value, [field]: next }
    // 筛选条件变化后回到第一页
    query.pageNum = 1
  }

  /** 移除某个 facet 的单个选中项 */
  function removeFacet(field: string, key: string): void {
    toggleFacet(field, key)
  }

  /** 清空所有筛选条件 */
  function clearFacets(): void {
    selectedFacets.value = {}
  }

  /** 设置页码 */
  function setPage(pageNum: number): void {
    query.pageNum = pageNum
  }

  /** 设置每页大小 */
  function setPageSize(pageSize: number): void {
    query.pageSize = pageSize
    query.pageNum = 1
  }

  /**
   * 加载本地历史到视图。
   *
   * 由页面在挂载时调用，而不是在 store 初始化时读 localStorage：
   * store 可能在 SSR 或不支持 localStorage 的环境里被创建，
   * 而"历史上没读出来"不该让 store 建不起来。
   */
  function loadLocalHistory(): void {
    keywordHistory.value = localHistory.keywordsOf(query.typeCode)
  }

  /**
   * 执行检索。
   *
   * 未指定 `typeCode` 时**安静地什么都不做**（并把上一轮结果清空），
   * 而不是弹一句「请先选择检索类型」。理由：搜索框现在允许不带类型直接搜，
   * 那时结果页展示的是网页采集那一组（见 `SearchResult.vue`），
   * 站内那一组会自己给出"未指定检索类型"的提示。
   * 这里再弹一次 warning，等于对一个**完全正常**的操作报错 ——
   * 用户会以为搜索失败了，而他看到的网页结果其实是成功的。
   *
   * 清空 result 是必须的：从"带类型的搜索"跳到"不带类型的搜索"时，
   * 留着上一轮的结果会让站内那一组继续显示与当前关键词无关的内容。
   */
  async function run(): Promise<SearchResponse | null> {
    if (!query.typeCode) {
      result.value = null
      return null
    }
    // 把选中态同步到 filters
    query.filters = { ...selectedFacets.value }
    loading.value = true
    try {
      const data = await searchApi.search({ ...query })
      result.value = data
      // 无论是否登录都记一份本地历史：未登录时它是唯一的历史来源；
      // 已登录时它让"换台设备/退出登录后仍能看到自己搜过什么"成立，
      // 而它**不会**被自动同步到账号（见 utils/localHistory.ts 的说明）。
      if (query.keyword) {
        keywordHistory.value = localHistory
          .push(query.keyword, query.typeCode)
          .filter((item) => item.tc === query.typeCode)
          .map((item) => item.k)
      }
      return data
    } catch {
      // 错误已由 axios 拦截器统一提示
      return null
    } finally {
      loading.value = false
    }
  }

  /** 重置检索结果 */
  function reset(): void {
    result.value = null
    selectedFacets.value = {}
    query.pageNum = 1
  }

  return {
    query,
    result,
    loading,
    selectedFacets,
    keywordHistory,
    setTypeCode,
    setKeyword,
    toggleFacet,
    removeFacet,
    clearFacets,
    setPage,
    setPageSize,
    loadLocalHistory,
    run,
    reset,
  }
})
