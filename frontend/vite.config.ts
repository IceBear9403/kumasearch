import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * 应用部署基路径（必须以 / 结尾）。
 *
 * 本项目与其它项目共用同一台机器 / 同一个域名，前端挂在 /kumasearch 下做区分
 * （同机 18081 端口上就住着另一个项目）。对外暴露的接口路径同样带这个前缀
 * （/kumasearch/api/**），由 Vite（开发）和 Nginx（生产）把前缀剥掉后转发给后端，
 * 所以**后端自己看到的接口路径始终是 /api/****，代码零改动。
 *
 * 这个值会同时影响两处，改它就等于改部署路径：
 *  1. 构建期：Vite 把它拼到 index.html 里的资源引用上（JS/CSS/favicon）；
 *  2. 运行期：`import.meta.env.BASE_URL` 由 vue-router 的 createWebHistory 读取，
 *     决定前端路由的前缀。
 *
 * ⚠️ 改这里必须同步改四处：
 *    - 下方 server.proxy 的 key 与 rewrite；
 *    - Nginx 的 location（docs/07-deployment.md §六 步骤 4）；
 *    - K8s Ingress 的 path（§九 9.5）；
 *    - 前端的 VITE_API_BASE_URL（frontend/.env.*，它必须等于 BASE_PATH + 'api'）。
 *    任一处不一致的典型症状是「首页能开、点进二级路由就 404」「资源全 404 但 HTML 是 200」
 *    或「页面正常但所有接口 404」。
 */
const BASE_PATH = '/kumasearch/'

// https://vite.dev/config/
export default defineConfig({
  base: BASE_PATH,
  plugins: [vue()],
  resolve: {
    alias: {
      // 源码根目录别名，需与 tsconfig.app.json 中的 paths 保持一致
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 前端 dev server 端口（Vite 默认）。后端在 18082，两者不要混。
    port: 5173,
    // 端口被占用时直接报错退出，而不是静默换成 5174。
    // 静默换端口会让"文档里的地址打不开"变成一个很难想到原因的谜题。
    strictPort: true,
    // 开发环境把接口代理到后端，避免跨域。
    //
    // 匹配的 key 是 /kumasearch/api 而不是 /api：本机与其它项目共用同一个域名，
    // 对外暴露的接口路径必须带项目前缀才能彼此区分（18081 端口上就住着另一个项目）。
    // 后端自己收到的仍是 /api/** —— 前缀由下面的 rewrite 剥掉，所以「后端接口路径不变」。
    //
    // 这样开发地址与生产 Nginx 下的地址完全一致：
    //   开发  http://localhost:5173/kumasearch/api/auth/login
    //   生产  http://<host>/kumasearch/api/auth/login
    proxy: {
      '/kumasearch/api': {
        target: 'http://localhost:18082',
        changeOrigin: true,
        // ⚠️ 这一行不能省：没有它，后端会收到 /kumasearch/api/auth/login，
        //    而它只认 /api/auth/login。
        //    实测踩坑记录：症状**不是 404，而是 401**（"未登录或登录已过期"）。
        //    因为未匹配的路径最终落到 Spring Security 的 anyRequest().authenticated() 上，
        //    于是"前缀没剥掉"看起来像"token 有问题"，非常容易排查错方向。
        rewrite: (path) => path.replace(/^\/kumasearch/, ''),
      },
    },
  },
  build: {
    // 构建分包：Element Plus、Vue 全家桶、其余第三方分别打包，利于浏览器缓存
    rollupOptions: {
      output: {
        manualChunks(id: string): string | undefined {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus') || id.includes('@element-plus')) {
            return 'element-plus'
          }
          if (
            id.includes('node_modules/vue/') ||
            id.includes('node_modules/@vue/') ||
            id.includes('vue-router') ||
            id.includes('node_modules/pinia/')
          ) {
            return 'vue-vendor'
          }
          return 'vendor'
        },
      },
    },
  },
})
