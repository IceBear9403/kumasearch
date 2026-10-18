import { storeToRefs } from 'pinia'

import { useSearchStore } from '@/stores/search'
import type { SearchRequest } from '@/types/search'

/**
 * 检索组合式函数：对 search store 的轻量封装，
 * 页面里直接用 const { result, loading, doSearch } = useSearch() 即可。
 */
export function useSearch() {
  const searchStore = useSearchStore()
  const { query, result, loading, selectedFacets } = storeToRefs(searchStore)

  /** 执行检索（可覆盖部分参数） */
  async function doSearch(override?: Partial<SearchRequest>) {
    if (override) {
      Object.assign(searchStore.query, override)
    }
    return searchStore.run()
  }

  return {
    query,
    result,
    loading,
    selectedFacets,
    setTypeCode: searchStore.setTypeCode,
    setKeyword: searchStore.setKeyword,
    toggleFacet: searchStore.toggleFacet,
    clearFacets: searchStore.clearFacets,
    setPage: searchStore.setPage,
    setPageSize: searchStore.setPageSize,
    doSearch,
    reset: searchStore.reset,
  }
}
