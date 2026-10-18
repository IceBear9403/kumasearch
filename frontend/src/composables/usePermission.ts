import { computed } from 'vue'
import { storeToRefs } from 'pinia'

import { useUserStore } from '@/stores/user'

/**
 * 权限判断组合式函数
 * 用法：const { hasPerm } = usePermission(); hasPerm('admin:user:manage')
 */
export function usePermission() {
  const userStore = useUserStore()
  const { roles, permissions, isAdmin, isLogin } = storeToRefs(userStore)

  /**
   * 是否拥有某个权限码。ADMIN 角色直接放行（见 store.hasPerm）。
   */
  function hasPerm(code: string): boolean {
    return userStore.hasPerm(code)
  }

  /** 是否拥有任意一个权限码 */
  function hasAnyPerm(codes: string[]): boolean {
    return codes.some((code) => userStore.hasPerm(code))
  }

  const isSuperAdmin = computed(() => roles.value.includes('ADMIN'))

  return { hasPerm, hasAnyPerm, roles, permissions, isAdmin, isLogin, isSuperAdmin }
}
