import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
// Element Plus 使用中文 locale
app.use(ElementPlus, { locale: zhCn })

// 全局注册 Element Plus 图标
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

const userStore = useUserStore()

// 若本地存在 token，尝试恢复登录态（失败由拦截器统一处理）。
//
// 等路由就绪后再发这一次请求，而不是立刻发：令牌过期时拦截器要看**当前路由**是不是
// 公开页面（公开页面只退回匿名浏览，不跳登录页，见 api/request.ts 的 handleAuthFailure）。
// 路由尚未就绪时 currentRoute 还是空的，那个判断会得出"不是公开页面"并把人踢去登录 ——
// 一个匿名用户会因此连搜索页都进不去。
void router.isReady().then(() => {
  if (userStore.isLogin) {
    userStore.fetchMe().catch(() => {
      /* 登录态失效由拦截器统一处理 */
    })
  }
})

app.mount('#app')
