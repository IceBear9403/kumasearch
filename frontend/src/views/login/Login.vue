<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import SliderCaptcha from '@/components/SliderCaptcha.vue'
import * as authApi from '@/api/auth'
import { useUserStore } from '@/stores/user'

/**
 * 登录页：密码登录 + 邮箱验证码登录，两种方式并列。
 *
 * <h3>滑块在这里是「两种方式不对称」的，这是照抄后端的设计</h3>
 * 后端只有**密码登录**在 `kuma.captcha.enabled=false` 时跳过滑块校验，
 * 另外三条（注册、邮箱码登录、发验证码）一律拒绝服务。
 * 理由见后端 `AuthService#verifyCaptchaForPasswordLogin`：密码登录是既有功能，
 * 让它因为一次配置默认值就登不进去，等于升级代码把管理员关在门外；
 * 而新增的三条链路是与人机校验一起引入的，"未配置"就该意味着"不可用"。
 *
 * <p>因此这里也必须不对称：
 * <ul>
 *   <li>密码登录：滑块不可用**不**禁用提交，仍按原样提交（后端会跳过校验）；</li>
 *   <li>邮箱码登录与发码：滑块不可用时禁用，因为提交必然被后端拒绝 ——
 *       与其让用户填完一次邮箱和验证码再看到「未开通」，不如一开始就说清楚。</li>
 * </ul>
 * 前端这样判断的依据只有「滑块能不能取到图」，因此这两种状态（未开通 / 已开通但未接入）
 * 在界面上是同一种表现。差异由后端的错误文案补足，这比前端去猜配置项可靠。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

type LoginTab = 'password' | 'email'
const activeTab = ref<LoginTab>('password')

const passwordFormRef = ref<FormInstance>()
const emailFormRef = ref<FormInstance>()
const loading = ref(false)

/* ------------------------------ 滑块 ------------------------------ */

const captchaVerification = ref('')
/** 滑块是否可用。由 SliderCaptcha 探测后告知 */
const captchaAvailable = ref(false)
const captchaRef = ref<InstanceType<typeof SliderCaptcha>>()

/**
 * 提交前是否需要一枚滑块凭证。
 *
 * 只有当滑块确实可用时才要求 —— 不可用时要求它，会让用户在密码登录这条路上
 * 永远卡在一个他无法完成的步骤上。
 */
const captchaRequired = computed(() => captchaAvailable.value)

/** 滑块探测结果变化时同步本页的判断依据 */
function onAvailability(value: boolean): void {
  captchaAvailable.value = value
}

/* ---------------------------- 密码登录 ---------------------------- */

const passwordForm = reactive({
  username: '',
  password: '',
})

const passwordRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/* -------------------------- 邮箱验证码登录 -------------------------- */

const emailForm = reactive({
  email: '',
  emailCode: '',
})

const emailRules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  emailCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
}

/** 发码倒计时（秒）。与后端 60 秒的发送节流对齐 */
const countdown = ref(0)
const sendingCode = ref(false)
let countdownTimer: number | undefined

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
 * 发送邮箱验证码。
 *
 * ⚠️ **不要**根据响应判断邮箱是否已注册：后端在 `scene=LOGIN` 下
 * 无论邮箱是否存在都返回同一个成功结果（不存在的邮箱走完全部限流与配额检查，
 * 只是不真的发信）。任何"看响应猜邮箱存不存在"的写法都会把后端刻意抹平的差异
 * 重新暴露出来，等于提供一个批量探测本站用户邮箱的工具。
 */
async function onSendCode(): Promise<void> {
  if (!emailForm.email) {
    ElMessage.warning('请先填写邮箱')
    return
  }
  if (!captchaVerification.value) {
    ElMessage.warning('请先完成滑块校验')
    return
  }
  sendingCode.value = true
  try {
    await authApi.sendEmailCode({
      email: emailForm.email,
      scene: 'LOGIN',
      captchaVerification: captchaVerification.value,
    })
    ElMessage.success('验证码已发送，请查收邮件（5 分钟内有效）')
    startCountdown()
    // 凭证是**一次性**的：它已经被发码接口消费掉了。
    // 不清空的话，接下来那一次登录提交会带着同一枚已作废的凭证，
    // 拿到「凭证已使用」这种让人摸不着头脑的报错。
    await captchaRef.value?.refresh()
  } finally {
    sendingCode.value = false
  }
}

/* ---------------------------- 提交 ---------------------------- */

/**
 * 提交前统一处理滑块凭证。
 *
 * @returns 可以继续提交时返回 true
 */
function ensureCaptcha(): boolean {
  if (captchaRequired.value && !captchaVerification.value) {
    ElMessage.warning('请先完成滑块校验')
    return false
  }
  return true
}

/** 登录成功后的共同收尾：询问是否导入本地历史，然后跳转 */
async function afterLoginSuccess(): Promise<void> {
  // 先问再跳：本地历史属于匿名行为，导不导入由用户决定。
  // 必须在跳转前问完 —— 跳走之后这个页面就没了，提示会跟丢。
  await userStore.offerHistoryImport()

  const redirect = route.query.redirect
  const target = typeof redirect === 'string' && redirect ? redirect : '/search'
  await router.replace(target)
}

/**
 * 提交失败后的共同收尾。
 *
 * **必须重新走一次滑块**：凭证一旦送到业务接口就被消费掉了（成功与失败都会消费），
 * 所以"密码打错了，改一下再提交"这条路，如果不刷新滑块，
 * 用户会一直收到「凭证已使用」—— 一个与他的输入毫无关系的错误。
 */
async function afterSubmitFailure(): Promise<void> {
  if (captchaAvailable.value) {
    await captchaRef.value?.refresh()
  }
}

async function onPasswordSubmit(): Promise<void> {
  if (!passwordFormRef.value) return
  await passwordFormRef.value.validate(async (valid) => {
    if (!valid || !ensureCaptcha()) return
    loading.value = true
    try {
      await userStore.login({
        username: passwordForm.username,
        password: passwordForm.password,
        // 滑块不可用时传空串：后端在未启用的情况下本就不校验它
        captchaVerification: captchaVerification.value,
      })
      ElMessage.success('登录成功')
      await afterLoginSuccess()
    } catch {
      // 错误提示已由 axios 拦截器统一处理
      await afterSubmitFailure()
    } finally {
      loading.value = false
    }
  })
}

async function onEmailSubmit(): Promise<void> {
  if (!emailFormRef.value) return
  await emailFormRef.value.validate(async (valid) => {
    if (!valid || !ensureCaptcha()) return
    loading.value = true
    try {
      await userStore.loginByEmail({
        email: emailForm.email,
        emailCode: emailForm.emailCode,
        captchaVerification: captchaVerification.value,
      })
      ElMessage.success('登录成功')
      await afterLoginSuccess()
    } catch {
      // 「邮箱不存在」与「验证码错误」在这里是同一个错误（后端刻意如此），
      // 因此不做任何分支处理 —— 能分出这两种情况就等于把邮箱探测能力交了出去。
      await afterSubmitFailure()
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="login-title">登录 KumaSearch</div>
      </template>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="密码登录" name="password">
          <el-form
            ref="passwordFormRef"
            :model="passwordForm"
            :rules="passwordRules"
            label-position="top"
            @submit.prevent="onPasswordSubmit"
          >
            <el-form-item label="用户名" prop="username">
              <el-input v-model="passwordForm.username" placeholder="请输入用户名" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="passwordForm.password"
                type="password"
                placeholder="请输入密码"
                show-password
                @keyup.enter="onPasswordSubmit"
              />
            </el-form-item>

            <el-form-item>
              <el-button
                type="primary"
                :loading="loading"
                class="login-btn"
                @click="onPasswordSubmit"
              >
                登录
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="邮箱验证码登录" name="email">
          <el-form
            ref="emailFormRef"
            :model="emailForm"
            :rules="emailRules"
            label-position="top"
            @submit.prevent="onEmailSubmit"
          >
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="emailForm.email" placeholder="请输入邮箱" clearable />
            </el-form-item>
            <el-form-item label="验证码" prop="emailCode">
              <div class="code-row">
                <el-input
                  v-model="emailForm.emailCode"
                  placeholder="6 位数字验证码"
                  maxlength="6"
                  @keyup.enter="onEmailSubmit"
                />
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

            <el-form-item>
              <el-button
                type="primary"
                :loading="loading"
                :disabled="!captchaAvailable"
                class="login-btn"
                @click="onEmailSubmit"
              >
                登录
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <!--
        滑块放在两个页签之外，**共用一个实例**，而不是每个页签各放一个：
        凭证本身就只有一枚，两处各自持有会让"切换页签后凭证还算不算数"
        变成一个没有正确答案的问题。
      -->
      <div class="captcha-block">
        <SliderCaptcha
          ref="captchaRef"
          v-model="captchaVerification"
          @availability="onAvailability"
        />
      </div>

      <div class="login-footer">
        <span>还没有账号？</span>
        <el-button link type="primary" @click="router.push('/register')">立即注册</el-button>
        <el-divider direction="vertical" />
        <el-button link @click="router.push('/search')">先去搜索（无需登录）</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 24px;
  background: linear-gradient(135deg, #e8f3ff 0%, #f5f7fa 100%);
}

.login-card {
  width: 420px;
}

.login-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

.login-btn {
  width: 100%;
}

.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.captcha-block {
  margin-bottom: 16px;
}

.code-btn {
  flex: 0 0 auto;
  white-space: nowrap;
}

.login-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #909399;
  font-size: 13px;
}
</style>
