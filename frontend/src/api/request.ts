import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'

import type { ApiResponse, LoginResponse } from '@/types/api'

/* ------------------------------------------------------------------ */
/* 令牌存储（集中在此处，避免 store <-> request 的循环依赖）           */
/* ------------------------------------------------------------------ */

const ACCESS_TOKEN_KEY = 'kuma_access_token'
const REFRESH_TOKEN_KEY = 'kuma_refresh_token'

export function getAccessToken(): string {
  return localStorage.getItem(ACCESS_TOKEN_KEY) ?? ''
}

export function getRefreshToken(): string {
  return localStorage.getItem(REFRESH_TOKEN_KEY) ?? ''
}

export function setAuthTokens(accessToken: string, refreshToken?: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  if (refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
  }
}

export function clearAuthTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/* ------------------------------------------------------------------ */
/* 业务异常                                                            */
/* ------------------------------------------------------------------ */

/** 业务错误（code !== 0），便于调用方按 code 分支处理 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

/** 后端约定的成功码 */
const SUCCESS_CODE = 0

/** 后端接口根路径。刻意保持在根路径 /api，不随前端基路径走 —— 即"后端接口路径不变"。 */
const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

/**
 * 应用部署基路径，形如 `/kumasearch/`（Vite 保证以 / 结尾，未配置 base 时是 `/`）。
 * 与 vite.config.ts 里的 BASE_PATH 是同一个值的两个视角。
 */
const APP_BASE = import.meta.env.BASE_URL

/**
 * 把浏览器地址栏的 pathname 换算成 vue-router 视角下的路径。
 *
 * 两者差一个基路径：`window.location.pathname` 是 `/kumasearch/search`，
 * 而 router 的 push/replace 只认 `/search`。混用会发生两件事：
 *  - 判断"当前是不是登录页"永远为假（前缀对不上）；
 *  - 跳转后变成 `/kumasearch/kumasearch/search` 双层前缀。
 * 这个 bug 只在部署到子路径时才出现，本地根路径开发时完全看不出来。
 */
function stripBase(pathname: string): string {
  if (!APP_BASE || APP_BASE === '/') return pathname
  const bare = APP_BASE.replace(/\/$/, '')
  if (pathname === bare) return '/'
  return pathname.startsWith(APP_BASE) ? '/' + pathname.slice(APP_BASE.length) : pathname
}

/** 带有重试标记的请求配置 */
interface RetryableConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

/* ------------------------------------------------------------------ */
/* 实例                                                                */
/* ------------------------------------------------------------------ */

/**
 * 主实例：所有业务请求都走它，带有统一的鉴权与响应处理。
 */
const http: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
})

/**
 * 刷新专用实例：独立于主实例，不挂任何拦截器。
 * 这样刷新接口自身返回 401 时不会被主实例的拦截器再次捕获，
 * 从根本上避免「刷新 -> 401 -> 再刷新」的无限递归。
 */
const refreshHttp: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' },
})

/* 请求拦截器：注入 Authorization */
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

/* ------------------------------------------------------------------ */
/* 令牌刷新（并发共享同一次刷新）                                      */
/* ------------------------------------------------------------------ */

/** 正在进行中的刷新 Promise，保证并发 401 只触发一次刷新 */
let refreshPromise: Promise<string> | null = null

function isRefreshUrl(url?: string): boolean {
  return !!url && url.includes('/auth/refresh')
}

/** 实际发起刷新请求（使用独立实例，避免递归） */
function requestNewToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    return Promise.reject(new ApiError(401, '登录已过期，请重新登录'))
  }
  return refreshHttp
    .post<ApiResponse<LoginResponse>>('/auth/refresh', { refreshToken })
    .then((resp: AxiosResponse<ApiResponse<LoginResponse>>) => {
      const body = resp.data
      if (!body || body.code !== SUCCESS_CODE || !body.data) {
        throw new ApiError(body?.code ?? -1, body?.message || '刷新登录态失败')
      }
      setAuthTokens(body.data.accessToken, body.data.refreshToken)
      return body.data.accessToken
    })
}

/**
 * 刷新 accessToken。多个并发 401 会复用同一个 refreshPromise，
 * 避免同一时刻发起 N 次刷新请求。
 */
function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = requestNewToken().finally(() => {
      // 无论成功失败都释放，后续 401 可重新触发
      refreshPromise = null
    })
  }
  return refreshPromise
}

/* ------------------------------------------------------------------ */
/* 登录态失效处理                                                      */
/* ------------------------------------------------------------------ */

let redirecting = false

/**
 * 本次失败的请求是否带了令牌。
 *
 * 这决定了 401 到底是「登录态过期」还是「这个接口本就需要登录」——
 * 两者的正确处理完全不同：
 *  - **带了令牌**：确实过期了。静默刷新后重放原请求，刷不出来才清登录态。
 *  - **没带令牌**：请求本来就是匿名的，没有登录态可供过期。此时发起刷新与跳转都是错的。
 *
 * 判断依据直接看请求自己带的头，而不是看此刻 localStorage 里有没有令牌：
 * 令牌可能在请求发出之后就被清掉了（并发 401 时必然如此），那样会得出错误答案。
 * 请求拦截器只在确实有令牌时才设置这个头，所以读它等于问「这次请求带令牌了吗」。
 *
 * 用可选调用而不是直接 `get()`：错误路径上的 `headers` 未必还是 AxiosHeaders 实例。
 */
function carriedToken(config?: RetryableConfig): boolean {
  return !!config?.headers?.get?.('Authorization')
}

/**
 * 登录态失效：清空本地令牌，并在**当前页面需要登录时**才跳转登录页。
 *
 * <h3>为什么公开页面上不跳转</h3>
 * 搜索本身匿名可用（路由上标了 `meta.public`）。一个令牌过期的用户正停在搜索页时，
 * 正确的结果是「安静地退回匿名」—— 他还能继续搜，右上角变回「登录 / 注册」。
 * 把他踢到登录页等于用一次令牌过期，把「搜索无需登录」这个功能又收了回去，
 * 而这恰恰是本次改造要立起来的东西。
 *
 * 「哪个页面需要登录」的权威是路由表，所以这里问它（`meta.public`），
 * 而不是在这里判断路径字符串 —— 后者每加一个公开页面都要回来改一次。
 */
function handleAuthFailure(): void {
  clearAuthTokens()
  // 动态引入，避免 request -> store/router 的循环依赖
  void import('@/stores/user')
    .then(({ useUserStore }) => {
      useUserStore().reset()
    })
    .catch(() => {
      /* 忽略 store 未初始化等异常 */
    })

  if (redirecting || stripBase(window.location.pathname).startsWith('/login')) return
  redirecting = true

  const current = stripBase(window.location.pathname) + window.location.search
  const target = `/login?redirect=${encodeURIComponent(current)}`
  void import('@/router')
    .then(({ default: router }) => {
      // 公开页面（搜索首页 / 结果页）：只退回匿名，不跳转。
      // 提示语用 warning 且措辞要说清"还能继续用"，因为这不是一次失败，只是一次降级。
      if (router.currentRoute.value.meta.public === true) {
        ElMessage.warning('登录状态已过期，已切换为匿名浏览')
        return
      }
      ElMessage.error('登录已过期，请重新登录')
      return router.replace(target)
    })
    .catch(() => {
      // 兜底的原生跳转必须自己补回基路径：base 是 router 的概念，window.location 不认
      ElMessage.error('登录已过期，请重新登录')
      window.location.href = APP_BASE.replace(/\/$/, '') + target
    })
    .finally(() => {
      // 稍后释放，覆盖同一批并发请求
      window.setTimeout(() => {
        redirecting = false
      }, 1000)
    })
}

/* ------------------------------------------------------------------ */
/* 响应拦截器                                                          */
/* ------------------------------------------------------------------ */

http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const body = response.data

    // 非标准响应体（例如文件流）直接返回原始数据
    if (body == null || typeof body !== 'object' || !('code' in body)) {
      return response.data as unknown as AxiosResponse
    }

    if (body.code !== SUCCESS_CODE) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new ApiError(body.code, body.message, body.traceId))
    }

    // 拦截器统一解包，业务层直接拿到 data
    return body.data as unknown as AxiosResponse
  },
  async (error: AxiosError<ApiResponse<unknown>>) => {
    // 主动取消的请求不提示
    if (axios.isCancel(error)) {
      return Promise.reject(error)
    }

    const config = error.config as RetryableConfig | undefined
    const status = error.response?.status

    if (status === 401) {
      // 匿名请求（没带令牌）的 401 不是「过期」，见 carriedToken 的说明：
      // 没有登录态可刷新、可失去，所以既不刷新也不跳转，只是这一次请求失败。
      // 提示语取服务端返回的文案（例如「未登录或登录已过期」），它比前端猜的信息更准。
      if (!carriedToken(config)) {
        ElMessage.error(error.response?.data?.message || '该功能需要登录后使用')
        return Promise.reject(error)
      }

      // 刷新接口自身 401 / 已重试过 -> 直接登出，防止无限循环
      if (!config || config._retry || isRefreshUrl(config.url)) {
        handleAuthFailure()
        return Promise.reject(error)
      }

      // 首次 401：标记后尝试静默刷新，成功则重放原请求
      config._retry = true
      try {
        const newToken = await refreshAccessToken()
        config.headers.set('Authorization', `Bearer ${newToken}`)
        return http.request(config)
      } catch (refreshError) {
        handleAuthFailure()
        return Promise.reject(refreshError)
      }
    }

    const message =
      error.response?.data?.message || error.message || '网络异常，请稍后重试'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

/* ------------------------------------------------------------------ */
/* 类型化的请求方法：拦截器已解包，T 即业务数据类型                    */
/* ------------------------------------------------------------------ */

export function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  // 响应拦截器已把 R<T> 解包为业务数据，这里做一次类型收敛
  return http.request(config) as unknown as Promise<T>
}

export function get<T = unknown>(
  url: string,
  params?: Record<string, unknown>,
  config?: AxiosRequestConfig,
): Promise<T> {
  return request<T>({ ...config, url, method: 'get', params })
}

export function post<T = unknown>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return request<T>({ ...config, url, method: 'post', data })
}

export function put<T = unknown>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  return request<T>({ ...config, url, method: 'put', data })
}

export function del<T = unknown>(
  url: string,
  params?: Record<string, unknown>,
  config?: AxiosRequestConfig,
): Promise<T> {
  return request<T>({ ...config, url, method: 'delete', params })
}

export default http
