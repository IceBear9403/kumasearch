<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import SliderCaptcha from '@/components/SliderCaptcha.vue'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import { useUserStore } from '@/stores/user'

/**
 * 注册页。
 *
 * <h3>⚠️ 这个页面在人机校验接入之前是不可用的，而且它必须表现成不可用</h3>
 * 后端对注册、发码、邮箱码登录三条链路一律要求滑块（`CaptchaVerifier#verify`
 * 在校验失败或未启用时抛异常），因此滑块取不到图 = 注册走不通。
 * 这里据此禁用提交与发码按钮，而不是让用户填完一整个表单再收到「未开通」——
 * 后一种做法会让人以为是自己填错了。
 *
 * <h3>校验顺序与后端一致（顺序本身是设计，不是巧合）</h3>
 * 后端注册的顺序是：滑块 → 密码强度 → 用户名/邮箱唯一性 → 邮箱验证码 → 建用户。
 * 前端按同样的顺序做即时校验，是为了让用户**在最便宜的一步**失败：
 * 密码强度是纯本地判断，把它排在邮箱验证码之前，
 * 可以避免"因为密码太短而白烧掉一枚一次性验证码"（那枚码发一次要等 60 秒）。
 */
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  email: '',
  emailCode: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

/**
 * 密码规则与后端 `PasswordPolicy` 对齐：8~128，且至少包含字母与数字。
 *
 * ⚠️ 前端的校验**只是提示**，真正的判定在后端。因此两边的规则必须一起改 ——
 * 前端写宽松了，用户会看到"本地通过、提交被拒"；写严了，用户会被一条
 * 服务端本来接受的密码挡住。这里刻意不加"必须含特殊字符"之类的规则：
 * 后端没有这条，加了就是前端单方面加码。
 */
const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*\d)[\S]{8,128}$/

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_-]{3,64}$/,
      message: '用户名由 3~64 位字母、数字、下划线或短横线组成',
      trigger: 'blur',
    },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      pattern: PASSWORD_RULE,
      message: '密码长度需在 8~128 之间，且至少包含字母与数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

/* ------------------------------ 滑块 ------------------------------ */

const captchaVerification = ref('')
const captchaAvailable = ref(false)
const captchaRef = ref<InstanceType<typeof SliderCaptcha>>()

/** 注册链路上滑块是必需的，没有"未启用就跳过"这回事 */
const submitDisabled = computed(() => !captchaAvailable.value || loading.value)

/** 滑块探测结果变化时同步本页的判断依据 */
function onAvailability(value: boolean): void {
  captchaAvailable.value = value
}

/* ---------------------------- 发验证码 ---------------------------- */

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

async function onSendCode(): Promise<void> {
  if (!form.email) {
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
      email: form.email,
      scene: 'REGISTER',
      captchaVerification: captchaVerification.value,
    })
    ElMessage.success('验证码已发送，请查收邮件（5 分钟内有效）')
    startCountdown()
    // 凭证已被发码接口消费，必须重新走一次滑块，否则后面的注册提交会带着
    // 一枚已作废的凭证去校验（一次性凭证的必然结果，见 Login.vue 同处的说明）
    await captchaRef.value?.refresh()
  } catch (e) {
    // REGISTER 场景是唯一允许暴露"邮箱已注册"的地方：注册页本来就必须提示用户去登录，
    // 而且这个信息通过用户名唯一性也能探到。LOGIN 场景则绝不能区分，见 api/auth.ts。
    if (e instanceof ApiError && e.code === 20402) {
      ElMessage.warning('该邮箱已注册，请直接登录')
    }
    // 其余错误已由拦截器统一提示
    await captchaRef.value?.refresh()
  } finally {
    sendingCode.value = false
  }
}

/* ------------------------------ 提交 ------------------------------ */

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (!captchaVerification.value) {
      ElMessage.warning('请先完成滑块校验')
      return
    }
    loading.value = true
    try {
      // 注册即登录：后端返回体与 login 完全一致，store 会直接落登录态
      await userStore.register({
        username: form.username,
        email: form.email,
        emailCode: form.emailCode,
        password: form.password,
        nickname: form.nickname || undefined,
        captchaVerification: captchaVerification.value,
      })
      ElMessage.success('注册成功，已为你自动登录')
      // 与登录同样处理：注册前很可能已经匿名搜过，问一次是否把本地记录带进新账号。
      // 这个问题在注册场景下更该问 —— 用户此刻正是在建立"以后要不要留痕"的那个账号。
      await userStore.offerHistoryImport()
      await router.replace('/search')
    } catch {
      // 错误已由拦截器提示。凭证无论成功失败都会被消费，因此必须重新滑一次，
      // 否则用户改完表单再提交只会一直看到「凭证已使用」。
      if (captchaAvailable.value) {
        await captchaRef.value?.refresh()
      }
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="register-page">
    <el-card class="register-card">
      <template #header>
        <div class="register-title">注册 KumaSearch</div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3~64 位字母、数字、_ 或 -" clearable />
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="用于登录与找回账号" clearable />
        </el-form-item>

        <el-form-item label="邮箱验证码" prop="emailCode">
          <div class="code-row">
            <el-input
              v-model="form.emailCode"
              placeholder="6 位数字验证码"
              maxlength="6"
              @keyup.enter="onSubmit"
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

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="8~128 位，至少包含字母与数字"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-form-item label="昵称（可选）">
          <el-input v-model="form.nickname" placeholder="不填则与用户名一致" clearable />
        </el-form-item>

        <el-form-item>
          <SliderCaptcha
            ref="captchaRef"
            v-model="captchaVerification"
            @availability="onAvailability"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            :disabled="submitDisabled"
            class="register-btn"
            @click="onSubmit"
          >
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="register-footer">
        <span>已有账号？</span>
        <el-button link type="primary" @click="router.push('/login')">去登录</el-button>
        <el-divider direction="vertical" />
        <el-button link @click="router.push('/search')">先去搜索（无需登录）</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.register-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
  background: linear-gradient(135deg, #e8f3ff 0%, #f5f7fa 100%);
}

.register-card {
  width: 460px;
}

.register-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

.register-btn {
  width: 100%;
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

.register-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: #909399;
  font-size: 13px;
}
</style>
