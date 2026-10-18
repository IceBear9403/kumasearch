import { get, post, put } from './request'

import type {
  CurrentUser,
  EmailCodeRequest,
  EmailLoginRequest,
  HistoryImportRequest,
  LoginRequest,
  LoginResponse,
  ProfileEmailRequest,
  ProfileNicknameRequest,
  ProfilePasswordRequest,
  RegisterRequest,
} from '@/types/api'

/** 登录 */
export function login(data: LoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/login', data)
}

/**
 * 发送邮箱验证码。
 *
 * ⚠️ 响应不携带"邮箱是否存在"的信息，且**这正是刻意的**：
 * `scene='LOGIN'` 时，无论邮箱是否存在都返回同一个成功结果
 * （不存在的邮箱走完全部限流与配额检查，只是不真的发信）。
 * 因此前端**不得**根据响应内容推断邮箱是否已注册 ——
 * 任何这样的推断都会把后端刻意抹平的差异重新暴露出来。
 *
 * `scene='REGISTER'` 是例外：邮箱已注册时会返回 20402（EMAIL_EXISTS），
 * 因为注册页本来就必须提示用户"去登录"。
 */
export function sendEmailCode(data: EmailCodeRequest): Promise<void> {
  return post<void>('/auth/email-code', data)
}

/** 注册。**注册即登录**，返回体与 login 完全一致。 */
export function register(data: RegisterRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/register', data)
}

/**
 * 邮箱验证码登录。返回体与 login 完全一致。
 *
 * ⚠️ 邮箱不存在与验证码错误返回**同一个错误码与同一条文案**（20008）。
 * 前端不要试图区分这两种情况 —— 能区分就等于把"批量探测本站用户邮箱"
 * 这个能力交了出去。
 */
export function loginByEmail(data: EmailLoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/login/email', data)
}

/**
 * 导入浏览器本地的搜索历史。**需登录**。
 *
 * 只能由用户显式触发（见 `utils/localHistory.ts` 的说明），
 * 不要在登录成功后自动调用 —— 那等于把匿名行为与实名账号静默关联起来。
 */
export function importHistory(data: HistoryImportRequest): Promise<void> {
  return post<void>('/auth/history/import', data)
}

/** 刷新令牌 */
export function refresh(refreshToken: string): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/refresh', { refreshToken })
}

/**
 * 登出。
 *
 * 必须把 refresh token 一并交给后端拉黑：它的有效期是 7 天，只拉黑 access token 的话，
 * 登出后这枚 refresh token 仍能换出新的 access token —— 登出就没有真正发生。
 */
export function logout(refreshToken: string): Promise<void> {
  return post<void>('/auth/logout', { refreshToken })
}

/** 获取当前登录用户信息 */
export function getCurrentUser(): Promise<CurrentUser> {
  return get<CurrentUser>('/auth/me')
}

/* ------------------------------------------------------------------ */
/* 个人信息维护（/auth/profile/**）                                     */
/*                                                                     */
/* 三个接口都作用于**当前登录用户自己**：用户 ID 由服务端从令牌里取，     */
/* 请求体里没有、也不接受 userId —— 有的话就是一个改别人账号的越权接口。*/
/* ------------------------------------------------------------------ */

/**
 * 修改昵称。需登录。
 *
 * 返回 `void`：昵称**不参与鉴权**，改它不作废令牌，因此服务端不换发新令牌。
 * （对比下面两个 —— 它们的返回值是必须处理的新令牌，不是可选信息。）
 */
export function changeNickname(data: ProfileNicknameRequest): Promise<void> {
  return put<void>('/auth/profile/nickname', data)
}

/**
 * 修改密码。需登录。
 *
 * ⚠️ **返回体里的新令牌必须落进本地登录态**，直接丢掉会让当前设备在下一个请求上掉线。
 * 原因是服务端把改密前的全部令牌都作废了；新令牌带着一个"作废之后"的签发时刻，
 * 因此它自己能过校验，而旧的那枚不能。处理方式见 `stores/user.ts` 的 `changePassword`。
 */
export function changePassword(data: ProfilePasswordRequest): Promise<LoginResponse> {
  return put<LoginResponse>('/auth/profile/password', data)
}

/**
 * 换绑邮箱。需登录。
 *
 * `emailCode` 必须是 `scene='CHANGE_EMAIL'` 发出的、且发往 **`newEmail`** 的验证码
 * —— 它证明的是"新邮箱归你所有"，而不是"旧邮箱是你的"（当前身份由令牌证明，够了）。
 *
 * 与 `changePassword` 同理：返回的新令牌必须落进本地登录态。
 */
export function changeEmail(data: ProfileEmailRequest): Promise<LoginResponse> {
  return put<LoginResponse>('/auth/profile/email', data)
}
