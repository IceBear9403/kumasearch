import type { Router } from 'vue-router'

import { useUserStore } from '@/stores/user'

/** 无需登录即可访问的白名单路径 */
const WHITE_LIST = ['/login', '/403']

/**
 * 注册全局前置守卫：
 *  - 未登录访问非白名单路由 -> 跳登录页并带 redirect
 *  - /admin/** 需要 ADMIN 角色或任一 admin:* 权限，否则跳 403
 *  - 带有 meta.permission 的路由还需校验对应权限码
 */
export function setupRouterGuard(router: Router): void {
  router.beforeEach((to) => {
    const userStore = useUserStore()

    // 同步页面标题
    const appTitle = import.meta.env.VITE_APP_TITLE || 'KumaSearch 搜索引擎'
    document.title = to.meta.title ? `${to.meta.title} - ${appTitle}` : appTitle

    const isPublic = to.meta.public === true || WHITE_LIST.includes(to.path)

    // 1. 登录校验
    if (!isPublic && !userStore.isLogin) {
      return {
        path: '/login',
        query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : {},
      }
    }

    // 2. 管理员区域校验
    const isAdminRoute = to.meta.admin === true || to.path.startsWith('/admin')
    if (isAdminRoute && !userStore.isAdmin) {
      return { path: '/403' }
    }

    // 3. 细粒度权限码校验
    if (to.meta.permission && !userStore.hasPerm(to.meta.permission)) {
      return { path: '/403' }
    }

    return true
  })
}
