import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 全局 UI 状态：侧边栏折叠、页面标题等
 */
export const useAppStore = defineStore('app', () => {
  /** 后台侧边栏是否折叠 */
  const sidebarCollapsed = ref(false)

  /** 当前应用标题 */
  const appTitle = ref(import.meta.env.VITE_APP_TITLE || 'KumaSearch 搜索引擎')

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setSidebarCollapsed(collapsed: boolean): void {
    sidebarCollapsed.value = collapsed
  }

  return { sidebarCollapsed, appTitle, toggleSidebar, setSidebarCollapsed }
})
