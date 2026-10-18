<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import SliderCaptcha from '@/components/SliderCaptcha.vue'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import { useUserStore } from '@/stores/user'

/**
 * 个人中心：改昵称 / 改密码 / 换绑邮箱。
 *
 * <h3>为什么这三个功能以前一个都没有</h3>
 * 改密码过去只能找管理员（走 `admin:user:manage`）。这意味着"我想换个密码"
 * 这件事必须经过另一个人，而那个人还得有后台权限 —— 对一个普通登录用户来说，
 * 账号里最私密的那一项他自己碰不到。这一页补的就是这个洞。
 *
 * <h3>三件事的后果各不相同，因此提示语必须分开写</h3>
 * - 改昵称：**不动作废令牌**。改完立即生效，谁都不会掉线。
 * - 改密码：作废该用户**全部**令牌（这正是改密码要的效果：把别处的会话踢掉），
 *   然后给当前设备换发一枚新令牌。因此本机无感，其它设备会掉线。
 * - 换绑邮箱：与改密码同样作废 + 换发。邮箱参与账号定位，变了就意味着凭证语境变了。
 *
 * 前两条的"其它设备掉线"必须明说 —— 用户以为只是改了个密码，
 * 结果另一台设备的登录没了，那看起来像故障。
 *
 * <h3>验证码发往**新**邮箱</h3>
 * 换绑时的验证码证明的是"新邮箱归你所有"，而"你是谁"已经由令牌证明了，
 * 不需要再验证旧邮箱。因此这里填哪个邮箱，码就发到哪个邮箱。
 */
const userStore = useUserStore()

/** 密码规则与后端 `PasswordPolicy` 对齐：8~128，且至少包含字母与数字 */
const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*\d)[\S]{8,128}$/

const activeTab = ref('nickname')

/* ------------------------------ 昵称 ------------------------------ */

const nicknameFormRef = ref<FormInstance>()
const nicknameSaving = ref(false)
const nicknameForm = reactive({ nickname: '' })

const nicknameRules: FormRules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 64, message: '昵称最长 64 个字符', trigger: 'blur' },
  ],
}

async function saveNickname(): Promise<void> {
  if (!nicknameFormRef.value) return
  try {
    await nicknameFormRef.value.validate()
  } catch {
    return
  }
  const value = nicknameForm.nickname.trim()
  if (!value) {
    ElMessage.warning('昵称不能为空')
    return
  }
  if (value === userStore.nickname) {
    ElMessage.info('昵称没有变化')
    return
  }
  nicknameSaving.value = true
  try {
    await userStore.changeNickname({ nickname: value })
    ElMessage.success('昵称已更新')
  } finally {
    nicknameSaving.value = false
  }
}

/* ------------------------------ 密码 ------------------------------ */

const passwordFormRef = ref<FormInstance>()
const passwordSaving = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      pattern: PASSWORD_RULE,
      message: '密码长度需在 8~128 之间，且至少包含字母与数字',
      trigger: 'blur',
    },
    {
      validator: (_rule, value: string, callback) => {
        if (value && value === passwordForm.oldPassword) {
          callback(new Error('新密码不能与当前密码相同'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function savePassword(): Promise<void> {
  if (!passwordFormRef.value) return
  try {
    await passwordFormRef.value.validate()
  } catch {
    return
  }
  passwordSaving.value = true
  try {
    // store 会把返回的新令牌写进本地登录态。**不做这一步的话，当前设备马上就会掉线** ——
    // 服务端已经把改密前的所有令牌作废了，而那枚旧的还攥在我们手里。
    await userStore.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
    })
    ElMessage.success('密码已修改。其它设备上的登录已失效，本机不受影响。')
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordFormRef.value.clearValidate()
  } finally {
    passwordSaving.value = false
  }
}

/* ------------------------------ 邮箱 ------------------------------ */

const emailFormRef = ref<FormInstance>()
const emailSaving = ref(false)
const emailForm = reactive({ newEmail: '', emailCode: '' })

const emailRules: FormRules = {
  newEmail: [
    { required: true, message: '请输入新邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  emailCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
}

async function saveEmail(): Promise<void> {
  if (!emailFormRef.value) return
  try {
    await emailFormRef.value.validate()
  } catch {
    return
  }
  if (emailForm.newEmail === userStore.email) {
    ElMessage.info('这已经是当前绑定的邮箱')
    return
  }
  emailSaving.value = true
  try {
    await userStore.changeEmail({
      newEmail: emailForm.newEmail,
      emailCode: emailForm.emailCode,
    })
    ElMessage.success('邮箱已换绑。之后可以用新邮箱登录，其它设备上的登录已失效。')
    emailForm.emailCode = ''
    emailFormRef.value.clearValidate()
  } finally {
    emailSaving.value = false
  }
}

/* ---------------------------- 发验证码 ---------------------------- */

const captchaVerification = ref('')
const captchaAvailable = ref(false)
const captchaRef = ref<InstanceType<typeof SliderCaptcha>>()
const countdown = ref(0)
const sendingCode = ref(false)
let countdownTimer: number | undefined

function onAvailability(value: boolean): void {
  captchaAvailable.value = value
}

function startCountdown(): void {
  countdown.value = 60
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      window.clearInterval(countdownTimer)
      countdownTimer = undefined
    }
  }, 1000)
}

onUnmounted(() => {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer)
  }
})

/**
 * 发送换绑验证码。
 *
 * ⚠️ 场景必须是 `CHANGE_EMAIL`。它与 `REGISTER` 的场景名不同，因此两边的码
 * **不能互相重放** —— 拿注册页发出来的码来换绑是无效的，这是刻意的。
 */
async function onSendCode(): Promise<void> {
  if (!emailForm.newEmail) {
    ElMessage.warning('请先填写新邮箱')
    return
  }
  if (!captchaVerification.value) {
    ElMessage.warning('请先完成滑块校验')
    return
  }
  sendingCode.value = true
  try {
    await authApi.sendEmailCode({
      email: emailForm.newEmail,
      scene: 'CHANGE_EMAIL',
      captchaVerification: captchaVerification.value,
    })
    ElMessage.success('验证码已发送到新邮箱，请查收（5 分钟内有效）')
    startCountdown()
    // 凭证已被发码接口消费，必须重新滑一次 —— 否则后面换绑会带着一枚已作废的凭证
    await captchaRef.value?.refresh()
  } catch (e) {
    // 换绑是**已登录用户**发起的，调用者身份确定，因此这里可以明确提示"该邮箱已被占用"。
    // 这与 LOGIN 场景的不可探测性不冲突：那边防的是匿名者试探谁在本站注册过。
    if (e instanceof ApiError && e.code === 20402) {
      ElMessage.warning('该邮箱已被其它账号使用')
    }
    await captchaRef.value?.refresh()
  } finally {
    sendingCode.value = false
  }
}

/* ------------------------------ 初始化 ------------------------------ */

const currentEmail = computed(() => userStore.email || '未绑定')

onMounted(async () => {
  nicknameForm.nickname = userStore.nickname
  // 邮箱**不在令牌里**，刷新页面后 store 里是空的，必须回查一次 /me 才拿得到。
  // 不查的后果是"已绑定的邮箱"显示成"未绑定"，而用户会据此以为自己没绑过。
  try {
    await userStore.fetchMe()
    nicknameForm.nickname = userStore.nickname
  } catch {
    /* 拦截器已提示；页面上其余功能不受影响 */
  }
})
</script>

<template>
  <div class="profile-page">
    <h2 class="page-title">个人中心</h2>

    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <!-- ------------------------------ 昵称 ------------------------------ -->
        <el-tab-pane label="昵称" name="nickname">
          <el-form
            ref="nicknameFormRef"
            :model="nicknameForm"
            :rules="nicknameRules"
            label-width="110px"
            class="pane-form"
          >
            <el-form-item label="当前昵称">
              <span class="current">{{ userStore.nickname || '（未设置）' }}</span>
            </el-form-item>
            <el-form-item label="新昵称" prop="nickname">
              <el-input v-model="nicknameForm.nickname" maxlength="64" clearable />
              <div class="field-hint">
                昵称只用于展示（顶部头像旁、评论区等），可以随时改，改完立即生效，
                不会导致任何设备掉线。
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="nicknameSaving" @click="saveNickname">
                保存
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ------------------------------ 密码 ------------------------------ -->
        <el-tab-pane label="修改密码" name="password">
          <el-alert type="warning" :closable="false" show-icon class="pane-tip">
            修改成功后<b>其它设备上的登录会全部失效</b>（这通常正是你想达到的效果），
            当前这台设备会自动换用新令牌，不需要重新登录。
          </el-alert>

          <el-form
            ref="passwordFormRef"
            :model="passwordForm"
            :rules="passwordRules"
            label-width="110px"
            class="pane-form"
          >
            <el-form-item label="当前密码" prop="oldPassword">
              <el-input
                v-model="passwordForm.oldPassword"
                type="password"
                show-password
                autocomplete="current-password"
              />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input
                v-model="passwordForm.newPassword"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="8~128 位，至少包含字母与数字"
              />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input
                v-model="passwordForm.confirmPassword"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="passwordSaving" @click="savePassword">
                修改密码
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ------------------------------ 邮箱 ------------------------------ -->
        <el-tab-pane label="换绑邮箱" name="email">
          <el-alert
            v-if="captchaAvailable === false"
            type="warning"
            :closable="false"
            show-icon
            class="pane-tip"
          >
            本站未启用滑块校验，发码链路不可用，因此暂时无法换绑邮箱。
          </el-alert>

          <el-form
            ref="emailFormRef"
            :model="emailForm"
            :rules="emailRules"
            label-width="110px"
            class="pane-form"
          >
            <el-form-item label="当前邮箱">
              <span class="current">{{ currentEmail }}</span>
            </el-form-item>
            <el-form-item label="新邮箱" prop="newEmail">
              <el-input v-model="emailForm.newEmail" placeholder="验证码会发到这个邮箱" clearable />
              <div class="field-hint">
                换绑后，新邮箱会成为登录凭据（邮箱验证码登录、找回账号都用它）。
                一个邮箱只能绑定一个账号。
              </div>
            </el-form-item>
            <el-form-item label="验证码" prop="emailCode">
              <div class="code-row">
                <el-input v-model="emailForm.emailCode" maxlength="6" placeholder="6 位数字" />
                <el-button
                  class="code-btn"
                  :loading="sendingCode"
                  :disabled="countdown > 0 || !captchaAvailable || !captchaVerification"
                  @click="onSendCode"
                >
                  {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <el-form-item label="人机校验">
              <SliderCaptcha
                ref="captchaRef"
                v-model="captchaVerification"
                @availability="onAvailability"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="emailSaving" @click="saveEmail">
                换绑邮箱
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.profile-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 16px;
}

.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.pane-form {
  max-width: 520px;
  margin-top: 8px;
}

.pane-tip {
  margin-bottom: 16px;
}

.current {
  color: #303133;
}

.field-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}

.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.code-btn {
  flex: 0 0 auto;
  white-space: nowrap;
}
</style>
