import { ref } from 'vue'

import * as searchApi from '@/api/search'
import type { SuggestDTO } from '@/types/search'

/**
 * 联想补全组合式函数：内置输入防抖与竞态处理。
 *
 * 竞态处理思路（双保险）：
 *  1. 请求序号 seq：每发起一次请求序号 +1，只有「最新序号」的响应才会被采用，
 *     网络乱序时先发后到的旧结果会被直接丢弃，避免下拉内容闪烁错乱；
 *  2. AbortController：新请求发起时取消上一个未完成的请求，减少无用流量。
 */
export function useSuggest(debounceMs = 200) {
  const suggestions = ref<SuggestDTO[]>([])
  const loading = ref(false)

  /** 每次请求自增，用于判定响应是否为最新 */
  let seq = 0
  /** 防抖定时器 */
  let timer: ReturnType<typeof setTimeout> | null = null
  /** 当前在途请求的取消控制器 */
  let controller: AbortController | null = null

  /** 立即请求（不经防抖） */
  async function fetchSuggest(typeCode: string, prefix: string, size = 10): Promise<void> {
    const keyword = prefix.trim()

    // 取消上一个在途请求
    controller?.abort()

    // typeCode 必填；prefix 可选（后端在 prefix 为空时仍会返回热门/历史建议）
    if (!typeCode) {
      seq += 1
      suggestions.value = []
      loading.value = false
      return
    }

    const currentSeq = ++seq
    controller = new AbortController()
    loading.value = true

    try {
      const data = await searchApi.suggest({ typeCode, prefix: keyword || undefined, size })
      // 只有最新一次请求的结果才被采用
      if (currentSeq !== seq) return
      suggestions.value = data ?? []
    } catch {
      // 取消/失败时，只有最新一次请求才清空列表
      if (currentSeq === seq) suggestions.value = []
    } finally {
      if (currentSeq === seq) loading.value = false
    }
  }

  /** 防抖请求：输入停止 debounceMs 毫秒后才真正发起 */
  function suggest(typeCode: string, prefix: string, size = 10): void {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      void fetchSuggest(typeCode, prefix, size)
    }, debounceMs)
  }

  /** 清空联想结果并取消在途请求 */
  function clear(): void {
    if (timer) clearTimeout(timer)
    controller?.abort()
    seq += 1
    suggestions.value = []
    loading.value = false
  }

  return { suggestions, loading, suggest, fetchSuggest, clear }
}
