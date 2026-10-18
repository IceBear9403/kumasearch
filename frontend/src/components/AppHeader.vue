<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Search, User } from '@element-plus/icons-vue'

import { useUserStore } from '@/stores/user'

/**
 * 全局头部：左侧品牌 + 右上角登录区。
 *
 * <h3>这里承载的正是「登录按需」这条要求</h3>
 * 未登录时右上角是「登录 / 注册」两个入口，**而搜索框、热词、结果页都不受任何影响** ——
 * 一个从头到尾不登录的用户可以完整使用搜索，只是他的搜索记录存在浏览器本地
 * （见 `utils/localHistory.ts`）。
 *
 * 「我的搜索历史」是唯一一个点击时才要求登录的入口：它点了会走路由守卫，
 * 守卫带上 `redirect`，登录完回到原处。把"什么时候需要登录"收敛到这一处，
 * 而不是在每个搜索动作前弹一次登录框 —— 后者会让"无需登录"变成"每步都要登录"。
 */
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/** 展示名优先取昵称，其次用户名 —— 都没有时才回落到「已登录」占位 */
const displayName = computed(
  () => userStore.nickname || userStore.username || '已登录',
)

/** 是否停在搜索相关页面，用于决定品牌点击是「回首页」还是「留下」 */
const onSearchPage = computed(() => route.path.startsWith('/search'))

function goHome(): void {
  if (onSearchPage.value) {
    return
  }
  void router.push('/search')
}

function goLogin(): void {
  // 带 redirect 是为了让「在结果页点登录」之后能回到这次搜索，而不是回首页重搜一遍
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function goRegister(): void {
  void router.push('/register')
}

/**
 * 退出登录后回搜索首页，**而不是回登录页**。
 *
 * 搜索匿名可用，把一个刚点了"退出"的人扔到登录页是在说
 * 「你得再登一次才能用」—— 与这个产品的前提相反。
 */
async function onLogout(): Promise<void> {
  await userStore.logout()
  void router.replace('/search')
}

/**
 * 是否有投稿入口。
 *
 * 用权限码判断而不是"是否登录"：投稿是**逐个权限**授出去的（`content:submit`），
 * 没有这个码的用户点进去只会撞到 403。藏起来是为了让他少白点一次，
 * 真正的拦截仍在路由守卫与后端。
 */
const canSubmit = computed(() => userStore.hasPerm('content:submit'))

function onCommand(command: string): void {
  switch (command) {
    case 'history':
      void router.push('/history')
      break
    case 'submit':
      void router.push('/submit')
      break
    case 'profile':
      void router.push('/profile')
      break
    case 'admin':
      void router.push('/admin/content')
      break
    case 'logout':
      void onLogout()
      break
    default:
      break
  }
}
</script>

<template>
  <header class="app-header">
    <div class="header-inner">
      <div class="brand" :class="{ clickable: !onSearchPage }" @click="goHome">
        <el-icon class="brand-icon"><Search /></el-icon>
        <span class="brand-name">KumaSearch</span>
      </div>

      <div class="header-right">
        <!-- 未登录：登录入口就放在这里，按需要才点 -->
        <template v-if="!userStore.isLogin">
          <el-button link @click="goLogin">登录</el-button>
          <el-button type="primary" plain size="small" @click="goRegister">注册</el-button>
        </template>

        <!-- 已登录：头像下拉 -->
        <el-dropdown v-else @command="onCommand">
          <span class="user-entry">
            <el-avatar :size="26" class="user-avatar">
              <el-icon><User /></el-icon>
            </el-avatar>
            <span class="user-name">{{ displayName }}</span>
            <el-icon class="user-arrow"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="history">我的搜索历史</el-dropdown-item>
              <el-dropdown-item v-if="canSubmit" command="submit">我的投稿</el-dropdown-item>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <!--
                管理后台入口只在确实有后台权限时出现。
                守卫也拦，这里也藏：守卫保证"进不去"，藏起来保证"不会白点一次"。
              -->
              <el-dropdown-item v-if="userStore.isAdmin" command="admin">
                管理后台
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  flex: 0 0 auto;
  background: #fff;
  border-bottom: 1px solid #f0f2f5;
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 56px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 18px;
  font-weight: 600;
  color: #409eff;
  letter-spacing: 1px;
}

.brand.clickable {
  cursor: pointer;
}

.brand-icon {
  font-size: 20px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-entry {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #303133;
  outline: none;
}

.user-avatar {
  background: #409eff;
  color: #fff;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-arrow {
  font-size: 12px;
  color: #909399;
}
</style>
