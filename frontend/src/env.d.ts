/// <reference types="vite/client" />

// 环境变量类型声明
interface ImportMetaEnv {
  /** 接口基础路径，默认 /api */
  readonly VITE_API_BASE_URL: string
  /** 应用标题 */
  readonly VITE_APP_TITLE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// 让 TS 认识 .vue 单文件组件
declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
