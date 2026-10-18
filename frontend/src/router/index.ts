import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { setupRouterGuard } from './guard'

/** 扩展路由元信息类型 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题 */
    title?: string
    /** 是否无需登录即可访问 */
    public?: boolean
    /** 是否属于管理后台 */
    admin?: boolean
    /** 访问所需的权限码 */
    permission?: string
  }
}

/**
 * 前台布局：带全局头部（右上角登录入口）。
 *
 * <h3>为什么用路由级布局，而不是在 App.vue 里按当前路由判断</h3>
 * 后者会把"哪个页面套哪个布局"这件事藏进组件里，每加一个页面都要回去改那个判断；
 * 而路由表本来就是描述"页面长什么样、挂在哪儿"的地方，布局放在这里才符合它的职责。
 *
 * <p>登录页、注册页与错误页<b>刻意不挂在它下面</b>：它们是全屏的居中卡片，
 * 套上头部会出现"页面还没登录，右上角已经在显示当前用户"的荒谬组合。
 * 不套的做法就是不给它们这个父路由 —— 不需要任何额外的标记或判断。
 */
const DefaultLayout = () => import('@/layouts/DefaultLayout.vue')

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/login/Register.vue'),
    // 注册必须匿名可达：此刻的用户正是"还没有账号"的人
    meta: { title: '注册', public: true },
  },
  {
    // 前台布局：搜索首页、结果页、我的历史都挂在它下面
    path: '/',
    component: DefaultLayout,
    redirect: '/search',
    children: [
      {
        path: 'search',
        name: 'SearchHome',
        component: () => import('@/views/search/SearchHome.vue'),
        // public：搜索无需登录即可使用。这是本次改造的核心要求 ——
        // 登录入口在右上角，按需登录，搜索本身任何时候都不弹登录。
        meta: { title: '搜索', public: true },
      },
      {
        path: 'search/result',
        name: 'SearchResult',
        component: () => import('@/views/search/SearchResult.vue'),
        meta: { title: '搜索结果', public: true },
      },
      {
        path: 'history',
        name: 'SearchHistory',
        component: () => import('@/views/search/SearchHistory.vue'),
        // ⚠️ 刻意**不加** public：这是个人数据。
        // 未登录时用户看到的应当是本地历史（在结果页与首页就能看到），
        // 而不是一个要求登录的页面 —— 所以这里跳登录是合理的，
        // 且守卫会带上 redirect，登录完回到原处。
        meta: { title: '我的搜索历史' },
      },
      {
        path: 'submit',
        name: 'SubmitList',
        component: () => import('@/views/content/SubmitList.vue'),
        // 需要权限码 content:submit（USER 角色与管理员都有）。
        // 挂在前台布局下而不是 /admin 下：它是普通用户的功能，
        // 套上后台布局会让人以为"投稿属于管理操作"。
        meta: { title: '我的投稿', permission: 'content:submit' },
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/Profile.vue'),
        // 不需要任何权限码：它只作用于当前登录用户自己。
        // 加一个权限码等于说"改自己的密码是需要授权的功能" —— 那是另一回事。
        meta: { title: '个人中心' },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/views/admin/AdminLayout.vue'),
    redirect: '/admin/content',
    meta: { title: '管理后台', admin: true },
    children: [
      {
        path: 'content',
        name: 'ContentList',
        component: () => import('@/views/admin/ContentList.vue'),
        meta: { title: '内容管理', admin: true, permission: 'admin:content:manage' },
      },
      {
        path: 'index-type',
        name: 'IndexTypeList',
        component: () => import('@/views/admin/IndexTypeList.vue'),
        meta: { title: '索引类型', admin: true, permission: 'admin:index:manage' },
      },
      {
        path: 'sync-task',
        name: 'SyncTaskList',
        component: () => import('@/views/admin/SyncTaskList.vue'),
        meta: { title: '同步任务', admin: true, permission: 'admin:sync:manage' },
      },
      {
        path: 'user',
        name: 'UserList',
        component: () => import('@/views/admin/UserList.vue'),
        meta: { title: '用户管理', admin: true, permission: 'admin:user:manage' },
      },
      {
        path: 'role',
        name: 'RoleList',
        component: () => import('@/views/admin/RoleList.vue'),
        meta: { title: '角色管理', admin: true, permission: 'admin:user:manage' },
      },
      {
        path: 'dict',
        name: 'DictList',
        component: () => import('@/views/admin/DictList.vue'),
        meta: { title: '数据字典', admin: true, permission: 'admin:dict:manage' },
      },
      {
        // 采集内容的人工审核。权限码复用 admin:content:manage ——
        // 「这条内容能不能对外展示」是同一个问题，不该有两个答案
        path: 'crawl-audit',
        name: 'CrawlAuditList',
        component: () => import('@/views/admin/CrawlAuditList.vue'),
        meta: { title: '采集审核', admin: true, permission: 'admin:content:manage' },
      },
      {
        // 采集种子站与队列。权限码复用 admin:sync:manage（与同步任务相同）：
        // 两者都是「让某个后台任务去某处取数据」，有权限配同步的人本就该能配采集
        path: 'crawl-seed',
        name: 'CrawlSeedList',
        component: () => import('@/views/admin/CrawlSeedList.vue'),
        meta: { title: '采集种子站', admin: true, permission: 'admin:sync:manage' },
      },
    ],
  },
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/error/Forbidden.vue'),
    meta: { title: '无访问权限', public: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/NotFound.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

// 注册登录守卫与管理员权限守卫
setupRouterGuard(router)

export default router
