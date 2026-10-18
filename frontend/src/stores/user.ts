import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import {
  clearAuthTokens,
  getAccessToken,
  getRefreshToken,
  setAuthTokens,
} from '@/api/request'
import type {
  CurrentUser,
  EmailLoginRequest,
  LoginRequest,
  LoginResponse,
  ProfileEmailRequest,
  ProfileNicknameRequest,
  ProfilePasswordRequest,
  RegisterRequest,
} from '@/types/api'
import * as localHistory from '@/utils/localHistory'

/**
 * 用户状态：token、用户信息、角色、权限
 * token 持久化在 localStorage，刷新页面后可自动恢复登录态。
 */
export const useUserStore = defineStore('user', () => {
  /* ----------------------------- state ----------------------------- */
  const accessToken = ref<string>(getAccessToken())
  const refreshToken = ref<string>(getRefreshToken())
  const userId = ref<number | null>(null)
  const username = ref('')
  const nickname = ref('')
  /**
   * 本人邮箱。**不在令牌里**，只来自 `/auth/me`（以及换绑成功后的本地写入），
   * 因此刷新页面后到 `/me` 返回之前它是 null —— 依赖它的地方要能接受"暂时没有"。
   */
  const email = ref<string | null>(null)
  const roles = ref<string[]>([])
  const permissions = ref<string[]>([])

  /* ---------------------------- getters ---------------------------- */
  const isLogin = computed(() => !!accessToken.value)
  /** 管理员：拥有 ADMIN 角色，或拥有任意 admin:* 权限 */
  const isAdmin = computed(
    () =>
      roles.value.includes('ADMIN') ||
      permissions.value.some((code) => code.startsWith('admin:')),
  )

  /* ---------------------------- actions ---------------------------- */

  /** 应用登录/刷新接口返回的令牌与用户信息 */
  function applyAuth(data: LoginResponse): void {
    // 换人了就把邮箱清掉：登录/刷新/换发令牌的响应里都**没有**邮箱（它只来自 /me），
    // 留着上一任用户的邮箱比空着更糟 —— 会让「个人中心」显示一个不属于当前账号的地址。
    // 同一个人的续期与改密换发时 userId 不变，因此不会误清；
    // 换绑邮箱则不是靠这里，而是由 changeEmail 显式写入新值。
    if (userId.value !== null && userId.value !== data.userId) {
      email.value = null
    }
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    setAuthTokens(data.accessToken, data.refreshToken)
    userId.value = data.userId
    username.value = data.username
    nickname.value = data.nickname
    roles.value = data.roles ?? []
    permissions.value = data.permissions ?? []
  }

  /** 应用 /auth/me 返回的用户信息 */
  function applyUser(user: CurrentUser): void {
    userId.value = user.userId
    username.value = user.username
    nickname.value = user.nickname
    email.value = user.email ?? null
    roles.value = user.roles ?? []
    permissions.value = user.permissions ?? []
  }

  /** 登录 */
  async function login(payload: LoginRequest): Promise<LoginResponse> {
    const data = await authApi.login(payload)
    applyAuth(data)
    return data
  }

  /**
   * 注册。**注册即登录** —— 后端返回体与 `login` 完全一致，因此这里也直接落登录态。
   *
   * 刻意不做「注册成功后跳到登录页让用户再输一次」：他刚刚证明过自己拥有那个邮箱
   * （验证码），再要求一次密码输入只是仪式，不增加任何安全性。
   */
  async function register(payload: RegisterRequest): Promise<LoginResponse> {
    const data = await authApi.register(payload)
    applyAuth(data)
    return data
  }

  /**
   * 邮箱验证码登录。返回体与 `login` 一致，同样直接落登录态。
   *
   * ⚠️ 后端对「邮箱不存在」与「验证码错误」返回**同一个错误码与同一条文案**（20008），
   * 因此这里不要试图按 code 区分后给出不同提示 —— 能区分就等于把
   * 「批量探测本站用户邮箱」这个能力送了出去。
   */
  async function loginByEmail(payload: EmailLoginRequest): Promise<LoginResponse> {
    const data = await authApi.loginByEmail(payload)
    applyAuth(data)
    return data
  }

  /**
   * 登录成功后询问「是否把浏览器里的匿名搜索记录导入账号」。
   *
   * <h3>为什么必须问，而不是自动导入</h3>
   * 未登录时存在浏览器里的搜索词是**匿名行为**，账号是实名的。
   * 自动合并等于把两者静默关联起来 —— 而用户当时没登录，
   * 他很可能正是因为"不想让这些词留在我的账号上"才没登录。
   * 这个决定属于用户，不属于实现。详见 `utils/localHistory.ts` 的展开。
   *
   * <h3>为什么由页面显式调用，而不是塞进 login/register 内部</h3>
   * 那会让 `login()` 在返回前弹出一个模态框并等它关掉 ——
   * 调用方（登录页）就无法在"登录成功"这一刻做自己的事（跳转、清表单），
   * 而且登录动作与一个可选的 UI 提示被绑死在一起。
   *
   * @returns 是否真的导入了（调用方一般不关心，供测试与埋点使用）
   */
  async function offerHistoryImport(): Promise<boolean> {
    if (!localHistory.hasAny()) {
      return false
    }
    try {
      await ElMessageBox.confirm(
        '检测到本机保存过一些搜索记录（未登录时记录的）。是否导入到当前账号？导入后仅你可见。',
        '导入本地搜索记录',
        {
          confirmButtonText: '导入',
          cancelButtonText: '不导入',
          type: 'info',
          // 选「不导入」不是错误操作，不该被记成一次异常
          distinguishCancelAndClose: true,
        },
      )
    } catch {
      // 用户选了不导入或直接关闭。本地记录**保留不清空** ——
      // 保留意味着他下次登录还能再被问一次，清空则等于替他做了决定。
      return false
    }

    // 本地历史按 typeCode 分开存，而后端一次只收一个 typeCode 的一组词
    const grouped = new Map<string, string[]>()
    for (const item of localHistory.list()) {
      const words = grouped.get(item.tc) ?? []
      words.push(item.k)
      grouped.set(item.tc, words)
    }

    let anyFailed = false
    for (const [typeCode, keywords] of grouped) {
      try {
        await authApi.importHistory({ typeCode, keywords })
      } catch {
        // 逐组降级：一组失败不该让已经成功的那几组白导。
        // 失败的组保留在本地，用户下次登录还能被问一次。
        anyFailed = true
      }
    }

    if (anyFailed) {
      ElMessage.warning('部分搜索记录未能导入，它们仍保存在本机')
      return false
    }

    localHistory.clear()
    ElMessage.success('搜索记录已导入')
    return true
  }

  /** 拉取当前用户信息（通常用于刷新页面后恢复登录态） */
  async function fetchMe(): Promise<CurrentUser> {
    const user = await authApi.getCurrentUser()
    applyUser(user)
    return user
  }

  /** 主动刷新令牌 */
  async function refreshTokens(): Promise<LoginResponse> {
    const data = await authApi.refresh(refreshToken.value)
    applyAuth(data)
    return data
  }

  /* ----------------------- 个人信息维护（改自己） ----------------------- */

  /**
   * 修改昵称。**不作废令牌**，因此本地登录态只需就地更新昵称再拉一遍用户信息。
   *
   * 为什么不像下面两个那样处理返回的令牌：后端根本不返回令牌 ——
   * 昵称不参与鉴权，作废它只会让用户莫名其妙被登出，而没有任何安全收益。
   */
  async function changeNickname(payload: ProfileNicknameRequest): Promise<void> {
    await authApi.changeNickname(payload)
    nickname.value = payload.nickname
  }

  /**
   * 修改密码。
   *
   * ⚠️ **返回的新令牌必须落进本地登录态，不能丢。**
   * 后端在这之前已经把该用户改密前的全部令牌作废了（这是改密码的意义所在：
   * 把其它设备踢下去），新令牌带的是一个"作废之后"的签发时刻。
   * 若不写入，当前设备手里那枚旧令牌在下一次请求上就会被判失效 ——
   * 用户刚看到"修改成功"，紧接着整个页面就掉线，体验上完全像一个 bug。
   */
  async function changePassword(payload: ProfilePasswordRequest): Promise<void> {
    const data = await authApi.changePassword(payload)
    applyAuth(data)
  }

  /**
   * 换绑邮箱。
   *
   * 与 {@link changePassword} 同理，必须写入返回的新令牌；邮箱额外在本地立刻更新，
   * 否则「个人中心」会显示改之前的地址（换发令牌的响应里没有邮箱字段）。
   */
  async function changeEmail(payload: ProfileEmailRequest): Promise<void> {
    const data = await authApi.changeEmail(payload)
    applyAuth(data)
    // applyAuth 只处理令牌与身份字段，邮箱单独写 —— 上面那句注释解释了为什么不能靠它。
    email.value = payload.newEmail
  }

  /** 仅清空本地登录态（不发请求），供 axios 拦截器在 401 时调用 */
  function reset(): void {
    accessToken.value = ''
    refreshToken.value = ''
    userId.value = null
    username.value = ''
    nickname.value = ''
    email.value = null
    roles.value = []
    permissions.value = []
    clearAuthTokens()
  }

  /**
   * 登出：调用后端把 access 与 refresh 两枚令牌都拉黑，失败也要清本地状态。
   *
   * 注意先取一份 refreshToken 的值再请求：reset() 会把它清空，
   * 而请求体需要在调用时就带上它。
   */
  async function logout(): Promise<void> {
    try {
      if (accessToken.value) {
        await authApi.logout(refreshToken.value)
      }
    } catch {
      // 忽略登出接口异常：服务端可能已经不可达，本地登录态仍然必须清掉。
      // 代价是那枚 refresh token 在服务端仍然有效（直到自然过期），
      // 这是可用性对安全性的一次让步 —— 让用户点了"退出"却还停在登录页，是更糟的结果。
    } finally {
      reset()
    }
  }

  /**
   * 权限判断：ADMIN 角色直接放行，否则查权限码数组。
   */
  function hasPerm(code: string): boolean {
    if (isAdmin.value) return true
    return permissions.value.includes(code)
  }

  return {
    accessToken,
    refreshToken,
    userId,
    username,
    nickname,
    email,
    roles,
    permissions,
    isLogin,
    isAdmin,
    applyAuth,
    applyUser,
    login,
    register,
    loginByEmail,
    offerHistoryImport,
    fetchMe,
    refreshTokens,
    changeNickname,
    changePassword,
    changeEmail,
    reset,
    logout,
    hasPerm,
  }
})
