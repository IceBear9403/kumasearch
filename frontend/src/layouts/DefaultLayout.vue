<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'

/**
 * 前台布局：全局头部 + 内容区。
 *
 * <h3>为什么需要这一层</h3>
 * 改造前 `App.vue` 只有 `<router-view />`，**没有全局头部**（只有管理后台有
 * `AdminLayout.vue`）。而「登录入口放在右上角、按需求登录」这个要求必须先有一个
 * 能放右上角登录区的层 —— 这是结构性改动，不是给某个页面加个按钮。
 *
 * <h3>谁挂在它下面、谁刻意不挂</h3>
 * 挂：搜索首页、搜索结果页、我的历史。
 * 不挂（因此看不到头部）：登录页、注册页、错误页 —— 它们是全屏居中卡片，
 * 套上头部会出现「页面还没登录，右上角却在显示当前用户」这种自相矛盾的画面。
 * 具体由路由表表达，见 `router/index.ts`：不挂的页面根本不在这个父路由的 children 里，
 * 不需要任何额外的标记位或 `if 当前路由` 判断。
 */
</script>

<template>
  <div class="default-layout">
    <AppHeader />
    <main class="default-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.default-layout {
  display: flex;
  flex-direction: column;
  /* 头部固定高度、内容区吃掉剩余空间：搜索首页要的 `height: 100%` 才有意义 */
  height: 100%;
  min-height: 100%;
}

.default-main {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
</style>
