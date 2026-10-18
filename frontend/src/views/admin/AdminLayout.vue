<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Checked,
  Coin,
  DataLine,
  Document,
  Fold,
  Expand,
  Link,
  List,
  Refresh,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'

import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { usePermission } from '@/composables/usePermission'

/**
 * 后台框架：侧边菜单 + 顶栏 + 面包屑
 */
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const { hasPerm } = usePermission()

interface MenuItem {
  path: string
  title: string
  icon: typeof Document
  permission: string
}

/** 菜单项；无权限的项会被过滤掉 */
const allMenus: MenuItem[] = [
  { path: '/admin/content', title: '内容管理', icon: Document, permission: 'admin:content:manage' },
  {
    path: '/admin/crawl-audit',
    title: '采集审核',
    icon: Checked,
    // 与「内容管理」同一个权限码：审核采集内容与审核站内内容是同一件事
    permission: 'admin:content:manage',
  },
  { path: '/admin/crawl-seed', title: '采集种子站', icon: Link, permission: 'admin:sync:manage' },
  { path: '/admin/index-type', title: '索引类型', icon: Coin, permission: 'admin:index:manage' },
  { path: '/admin/sync-task', title: '同步任务', icon: Refresh, permission: 'admin:sync:manage' },
  { path: '/admin/user', title: '用户管理', icon: User, permission: 'admin:user:manage' },
  { path: '/admin/role', title: '角色管理', icon: List, permission: 'admin:user:manage' },
  { path: '/admin/dict', title: '数据字典', icon: DataLine, permission: 'admin:dict:manage' },
]

const menus = computed(() => allMenus.filter((item) => hasPerm(item.permission)))

/** 面包屑：管理后台 + 当前页标题 */
const breadcrumbs = computed(() =>
  route.matched
    .filter((item) => item.meta?.title)
    .map((item) => ({ path: item.path, title: item.meta.title as string })),
)

async function onLogout(): Promise<void> {
  await userStore.logout()
  await router.replace('/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside :width="appStore.sidebarCollapsed ? '64px' : '200px'" class="admin-aside">
      <div class="admin-logo">{{ appStore.sidebarCollapsed ? 'K' : 'KumaSearch' }}</div>
      <el-menu
        :default-active="route.path"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        router
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="appStore.toggleSidebar()">
            <Fold v-if="!appStore.sidebarCollapsed" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item
              v-for="item in breadcrumbs"
              :key="item.path"
            >
              {{ item.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header-right">
          <el-dropdown>
            <span class="user-info">
              {{ userStore.nickname || userStore.username || '未登录' }}
              <el-icon><SwitchButton /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/search')">前台搜索</el-dropdown-item>
                <el-dropdown-item divided @click="onLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100%;
}

.admin-aside {
  background: #001529;
  transition: width 0.2s;
  overflow: hidden;
}

.admin-logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-weight: 600;
  letter-spacing: 1px;
}

.admin-aside :deep(.el-menu) {
  border-right: none;
  background: transparent;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #f0f2f5;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  cursor: pointer;
  font-size: 18px;
}

.user-info {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: #303133;
}

.admin-main {
  background: #f5f7fa;
}
</style>
