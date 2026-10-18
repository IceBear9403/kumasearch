/**
 * 通用接口类型
 */

/** 后端统一响应体 */
export interface ApiResponse<T = unknown> {
  /** 业务状态码，0 表示成功 */
  code: number
  /** 提示信息 */
  message: string
  /** 业务数据 */
  data: T
  /** 服务端时间戳（毫秒） */
  timestamp: number
  /** 链路追踪 ID */
  traceId?: string
}

/** R<T> 是 ApiResponse<T> 的简写别名，便于书写 */
export type R<T = unknown> = ApiResponse<T>

/** 分页查询基础参数 */
export interface PageQuery {
  pageNum?: number
  pageSize?: number
}

/** 分页结果（非检索类列表接口使用） */
export interface PageResult<T> {
  /** 当前页数据（后端字段名是 records，不是 list） */
  records: T[]
  /** 总命中数；深分页模式或 trackTotalHits=false 时为 null */
  total: number | null
  /** 当前页码，从 1 开始；游标翻页模式下为 null */
  pageNum: number | null
  /** 每页大小 */
  pageSize: number
  /** 总页数；total 为 null 时为 null */
  pages: number | null
  /** 游标翻页模式下的下一页游标；为 null 表示已到末页 */
  nextCursor: string | null
}

/* ------------------------------------------------------------------ */
/* 认证相关                                                            */
/* ------------------------------------------------------------------ */

/** 登录请求 */
export interface LoginRequest {
  username: string
  password: string
  /**
   * 滑块校验凭证。
   *
   * 后端是否要求它取决于 `kuma.captcha.enabled`：未启用时后端不校验、
   * 前端也不需要产生它，因此这里是可选的。**不要**在前端写死"必传" ——
   * 那会让未启用验证码的部署连密码登录都发不出去。
   */
  captchaVerification?: string
}

/* ------------------------------------------------------------------ */
/* 注册 / 邮箱验证码登录                                                */
/* ------------------------------------------------------------------ */

/**
 * 邮箱验证码的使用场景。
 *
 * 与后端 `EmailScene` 一一对应。**场景进 key**，因此注册页拿到的验证码
 * 不能用于登录，反之亦然 —— 跨场景重放是邮箱验证码实现里最经典的漏洞。
 *
 * `CHANGE_EMAIL`（个人中心换绑邮箱）与 `REGISTER` 共用"该邮箱必须没人用"这条检查，
 * 但两者发出来的码同样**不能互相重放**：场景名进 key。
 * 它的验证码发往**新**邮箱 —— 见 `ProfileEmailRequest` 的类注释。
 */
export type EmailScene = 'REGISTER' | 'LOGIN' | 'CHANGE_EMAIL'

/** 发送邮箱验证码请求 */
export interface EmailCodeRequest {
  email: string
  scene: EmailScene
  captchaVerification: string
}

/** 注册请求 */
export interface RegisterRequest {
  username: string
  email: string
  /** 6 位数字验证码 */
  emailCode: string
  password: string
  nickname?: string
  captchaVerification: string
}

/** 邮箱验证码登录请求 */
export interface EmailLoginRequest {
  email: string
  emailCode: string
  captchaVerification: string
}

/** 导入本地搜索历史请求 */
export interface HistoryImportRequest {
  typeCode: string
  /** 最多 50 条，按时间倒序（最近的在前） */
  keywords: string[]
}

/** 登录 / 刷新成功后返回的令牌与用户信息 */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  /** accessToken 有效期（秒） */
  expiresIn: number
  userId: number
  username: string
  nickname: string
  roles: string[]
  permissions: string[]
}

/**
 * GET /auth/me 返回的当前用户信息。
 *
 * `nickname` 与 `email` 由后端**回查数据库**填充（它们不在令牌里），
 * 而 `username` / `roles` / `permissions` 取自令牌 —— 见后端
 * `UserInfoResponse` 的类注释。前端只看值、不关心来源。
 */
export interface CurrentUser {
  userId: number
  username: string
  nickname: string
  /** 本人邮箱；未绑定邮箱的用户为 null */
  email?: string | null
  roles: string[]
  permissions: string[]
}

/* ------------------------------------------------------------------ */
/* 个人信息维护（/auth/profile/**）                                     */
/* ------------------------------------------------------------------ */

/** 修改昵称请求 */
export interface ProfileNicknameRequest {
  nickname: string
}

/**
 * 修改密码请求。
 *
 * 需要当前密码：这个接口只凭一枚令牌就能调，不验旧密码的话，
 * 任何拿到令牌的人都能直接把账号锁掉。
 */
export interface ProfilePasswordRequest {
  oldPassword: string
  newPassword: string
}

/**
 * 换绑邮箱请求。
 *
 * 验证码发往 `newEmail`（场景 `CHANGE_EMAIL`），证明的是"新邮箱归你所有"。
 */
export interface ProfileEmailRequest {
  newEmail: string
  emailCode: string
}

/** 刷新令牌请求 */
export interface RefreshTokenRequest {
  refreshToken: string
}
