<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import * as captchaApi from '@/api/captcha'
import type { CaptchaChallenge } from '@/types/captcha'

/**
 * 滑块人机校验（AJ-Captcha 的块拼图）。
 *
 * # 它对外只做两件事
 *
 * 1. 通过 `@availability` 告诉父组件「滑块能不能用」—— 父组件据此决定提交按钮能不能点；
 * 2. 用户完成滑动后，把**一次性凭证**通过 `v-model` 交出去，父组件原样填进
 *    注册 / 登录 / 发码请求的 `captchaVerification` 字段。
 *
 * # 判定在服务端，这里只负责"把用户滑到哪儿"报上去
 *
 * 正确坐标留在服务端的缓存里（`GET /api/captcha/get` 不会下发它），本组件做的是：
 * 把拖动距离换算成**背景图原始像素**的 x，连同 y 一起 POST 给 `/api/captcha/check`，
 * 由服务端判断对错。任何"照着响应自己算出滑到哪"的写法都等于把答案写在客户端。
 *
 * # ⚠️ y 为什么是一个写死的 5
 *
 * 上游 `check` 对坐标的判定是：`|x - 正确x| <= slipOffset`，而 **y 必须完全相等**。
 * 上游生成题目时（`generateJigsawPoint`）算出的 y 是：
 * 背景图高度与拼图块高度**相等**时取常量 `5`，不相等时取 `随机数 + 5`。
 * jar 内自带的底图是 620×310、拼图块是 94×310，高度相等 —— 因此 y 恒为 5。
 *
 * 这不是"猜的"，是反编译核出来的，而且它有明确的失效条件：
 * **如果将来换成高度不等于底图的拼图块模板，y 会变成随机数**，那时候所有滑动
 * 都会以「坐标错误」失败，而本组件完全无从得知那个随机值。
 * 届时唯一的解法是自定义模板路径 + 同步改这个常量 —— 因此这里把它写成具名常量
 * 并留下这段说明，而不是把 5 散落在拼接语句里。
 */
const POINT_Y = 5

defineProps<{
  /** 一次性凭证。父组件提交业务请求时原样带上 */
  modelValue?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  /** 滑块是否可用。只在探测结果变化时发出 */
  (e: 'availability', value: boolean): void
}>()

/** 取题中（含首次加载与每次换题） */
const probing = ref(false)
/** 滑块是否可用 */
const available = ref(false)
/** 不可用的原因（面向使用者的话术） */
const unavailableReason = ref('')

/** 当前这道题。null 表示还没有题可做 */
const challenge = ref<CaptchaChallenge | null>(null)
/** 校验请求在途 */
const verifying = ref(false)
/** 正在拖动 */
const dragging = ref(false)

/** 滑块已滑出的比例（0 ~ maxRatio），是唯一的位置状态 */
const ratio = ref(0)
/** 背景图的原始像素宽度，换算 x 用它 */
const bgNaturalWidth = ref(0)
/** 拼图块的原始像素宽度，决定滑块能滑到哪儿 */
const pieceNaturalWidth = ref(0)

const stageRef = ref<HTMLElement>()
const bgRef = ref<HTMLImageElement>()
const pieceRef = ref<HTMLImageElement>()

/** 拖动开始时的指针位置与当时的比例，用于算增量而不是绝对定位 */
let dragStartX = 0
let dragStartRatio = 0

/**
 * 两个原始宽度都拿到了才允许拖动。
 *
 * 少了它们，`ratio → x` 的换算无从谈起（或者会用一个默认值瞎算），
 * 表现为"拖了但永远对不上"。所以宁可先不让拖。
 */
const sizesReady = computed(() => bgNaturalWidth.value > 0 && pieceNaturalWidth.value > 0)

/**
 * 滑块的上限：拼图块的左边缘最多推到背景图右边缘。
 *
 * 上游出题时正确 x 的取值范围是 [200, 背景宽 - 块宽]，
 * 所以轨道必须能让滑块走完整程 —— 轨道短了会让一部分题目根本无法作答。
 */
const maxRatio = computed(() => {
  if (!sizesReady.value) return 0
  return 1 - pieceNaturalWidth.value / bgNaturalWidth.value
})

/** 背景图与拼图块的 data URL（后端下发的是不带前缀的裸 Base64） */
const bgUrl = computed(() =>
  challenge.value ? `data:image/jpeg;base64,${challenge.value.originalImageBase64}` : '',
)
const pieceUrl = computed(() =>
  challenge.value ? `data:image/png;base64,${challenge.value.jigsawImageBase64}` : '',
)

/**
 * 拼图块的位置与尺寸。
 *
 * 全部用百分比表达，不测量任何像素：两张图来自同一张底图、同一个缩放系数，
 * 所以 `块宽/底图宽` 这个比例在原始像素和渲染像素下是同一个数。
 * 用百分比就不需要在窗口大小变化时重新计算 —— 那类"改了窗口就再也对不上"的 bug
 * 正是这么来的。
 */
const pieceStyle = computed(() => ({
  left: `${ratio.value * 100}%`,
  width: sizesReady.value ? `${(pieceNaturalWidth.value / bgNaturalWidth.value) * 100}%` : '0',
  // 尺寸未知时先藏起来：此时 width 还是 0，会渲染成一条贴在左边的细线，
  // 看起来像"图没加载出来"。拖动本身由 sizesReady 拦着，不依赖这个透明度。
  opacity: sizesReady.value ? 1 : 0,
}))

/**
 * 取（换）一道题。
 *
 * 挂载时跑一次；拖动失败后由 `submit` 调用；父组件在提交失败后也会调 `refresh`。
 * **不做自动重试**：后端返回的是「未开通」这类确定性失败，重试一百次结果一样。
 */
async function probe(): Promise<void> {
  probing.value = true
  unavailableReason.value = ''
  // 旧题一律作废：它的凭证已经无效（被消费或随题过期），
  // 留着会让父组件以为手上那枚还能用。
  challenge.value = null
  bgNaturalWidth.value = 0
  pieceNaturalWidth.value = 0
  ratio.value = 0
  emit('update:modelValue', '')

  try {
    challenge.value = await captchaApi.getCaptcha()
    available.value = true
    emit('availability', true)
  } catch (e) {
    // 拦截器已经弹过一次后端文案了，这里只留一句面向使用者的话，
    // 避免同一个原因在界面上说两遍（一次弹窗、一次内联）。
    unavailableReason.value = readableReason(e)
    available.value = false
    emit('availability', false)
  } finally {
    probing.value = false
  }
}

/**
 * 把后端/网络的失败翻译成使用者能理解的一句话。
 *
 * 后端的「人机校验未开通（kuma.captcha.enabled=false）」是写给运维看的，
 * 原样展示给注册用户等于让他去读一个他不该关心的配置项。
 */
function readableReason(e: unknown): string {
  const message = e instanceof Error ? e.message : ''
  if (message.includes('未开通')) {
    return '人机校验暂未开通，注册与邮箱验证码登录暂时不可用。'
  }
  return '人机校验加载失败，请稍后重试。'
}

/**
 * 图片加载完成时记下原始尺寸。
 *
 * 每次都重新赋值（而不是只在为 0 时赋值）：换题后是两张新图，
 * 用旧尺寸算出的比例会让滑块与缺口错位，且看起来像"服务端判定有问题"。
 */
function onImageLoad(): void {
  if (bgRef.value?.naturalWidth) {
    bgNaturalWidth.value = bgRef.value.naturalWidth
  }
  if (pieceRef.value?.naturalWidth) {
    pieceNaturalWidth.value = pieceRef.value.naturalWidth
  }
}

/**
 * 图片加载失败：它是一张 data URL，加载不出来只可能是响应内容不对。
 *
 * 必须显式处理 —— 否则界面会是一个"永远拖不动"的空框，用户只会以为是自己没操作对。
 */
function onImageError(): void {
  available.value = false
  emit('availability', false)
  unavailableReason.value = '滑块图片加载失败，请点重试。'
}

function onPointerDown(event: PointerEvent): void {
  if (!sizesReady.value || verifying.value) return
  dragging.value = true
  dragStartX = event.clientX
  dragStartRatio = ratio.value
  // 指针捕获：拖到图片外（甚至拖出浏览器窗口）也能继续收到 move/up。
  // 不捕获的话，用户拖快一点松手，指针已经在元素外面，up 事件收不到，
  // 滑块就卡在"按下"状态再也动不了。
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value) return
  const stageWidth = stageRef.value?.clientWidth ?? 0
  if (stageWidth <= 0) return
  const next = dragStartRatio + (event.clientX - dragStartX) / stageWidth
  ratio.value = Math.min(Math.max(next, 0), maxRatio.value)
}

function onPointerUp(event: PointerEvent): void {
  if (!dragging.value) return
  dragging.value = false
  const target = event.currentTarget as HTMLElement
  if (target.hasPointerCapture?.(event.pointerId)) {
    target.releasePointerCapture(event.pointerId)
  }
  void submit()
}

/** 提交轨迹，换回一次性凭证。 */
async function submit(): Promise<void> {
  const current = challenge.value
  if (!current || !sizesReady.value || verifying.value) return

  verifying.value = true
  try {
    // x 换算成**背景图原始像素**：渲染出来是 320px 宽还是 620px 宽由浏览器决定，
    // 而服务端只认原始像素。
    const x = Math.round(ratio.value * bgNaturalWidth.value)
    const result = await captchaApi.checkCaptcha({
      captchaId: current.captchaId,
      // 这个 JSON 是**原样**参与服务端凭证计算的（见后端 AjCaptchaVerifier#buildVerification），
      // 所以不能重排字段、不能加空格 —— 本地重算一遍只是为了"看起来干净"，
      // 代价却是让凭证对不上。
      trackList: JSON.stringify({ x, y: POINT_Y }),
    })
    emit('update:modelValue', result.captchaVerification)
  } catch {
    // 失败原因（滑歪了 / 题目过期 / 被限流）拦截器已经弹过提示，这里不重复。
    //
    // 关键动作是**换一道新题**：上游在一次 check 里就会把这道题从缓存里删掉，
    // 同一个 captchaId 再提交只会得到「验证码已失效」。不换题的后果是
    // 用户第二次拖动必然失败，他会以为是自己的问题，反复拖也过不去。
    await probe()
  } finally {
    verifying.value = false
  }
}

/**
 * 作废当前凭证并重新走一次滑块。
 *
 * 由父组件在一次提交失败后调用：凭证是**一次性**的，被业务接口消费掉之后，
 * 同一个字符串再提交会被判成「已使用」。所以"提交失败后改一下再提交"
 * 这条路必须建立在重新滑一次之上，否则用户会看到一个永远失败的表单。
 */
async function refresh(): Promise<void> {
  emit('update:modelValue', '')
  await probe()
}

defineExpose({ refresh })

/** 拖动期间禁止选中文本，松手时解除（`user-select` 也写在样式里，这里兜住动态场景） */
function toggleTextSelection(disabled: boolean): void {
  document.body.style.userSelect = disabled ? 'none' : ''
}

onMounted(probe)

onBeforeUnmount(() => {
  // 组件在拖动中被卸载（例如点了"切换到注册页"）会把这个内联样式留在 body 上，
  // 表现为整站文字都选不中 —— 只在拖动时才设，卸载时必须还原。
  toggleTextSelection(false)
})
</script>

<template>
  <div class="slider-captcha">
    <el-skeleton v-if="probing" :rows="1" animated />

    <el-alert
      v-else-if="!available"
      type="warning"
      :closable="false"
      show-icon
      class="captcha-alert"
    >
      <template #title>{{ unavailableReason || '人机校验不可用' }}</template>
      <el-button link type="primary" size="small" @click="probe">
        <el-icon><Refresh /></el-icon>
        重试
      </el-button>
    </el-alert>

    <template v-else>
      <div
        ref="stageRef"
        class="stage"
        :class="{ 'is-dragging': dragging }"
        @pointerdown="toggleTextSelection(true)"
        @pointerup="toggleTextSelection(false)"
      >
        <img ref="bgRef" class="bg" :src="bgUrl" alt="" draggable="false" @load="onImageLoad" @error="onImageError" />
        <!--
          拼图块本身就是拖动把手：它带着抓手形状，用户一看就知道要拖它。
          轨道长度等于整张背景图，因为正确缺口可能出现在很靠右的位置。
        -->
        <img
          ref="pieceRef"
          class="piece"
          :src="pieceUrl"
          :style="pieceStyle"
          alt=""
          draggable="false"
          @load="onImageLoad"
          @error="onImageError"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        />
      </div>
      <p class="captcha-hint">
        {{ verifying ? '校验中…' : '按住拼图块拖到缺口处，松手即校验' }}
      </p>
    </template>

    <!-- 凭证已有：这是父组件提交表单的必要条件，值得明确显示出来 -->
    <p v-if="modelValue" class="captcha-done">已完成人机校验</p>
  </div>
</template>

<style scoped>
.slider-captcha {
  width: 100%;
}

.captcha-alert {
  align-items: flex-start;
}

.stage {
  position: relative;
  width: 100%;
  border-radius: 4px;
  overflow: hidden;
  line-height: 0;
  /* 触摸设备上必须关掉默认手势，否则拖动变成滚动页面，滑块永远拖不动 */
  touch-action: none;
  user-select: none;
}

.stage .bg {
  display: block;
  width: 100%;
}

.stage .piece {
  position: absolute;
  top: 0;
  height: 100%;
  cursor: grab;
  /* 拖动中的过渡会让滑块"追手"，故意不加 transition */
}

.stage.is-dragging .piece {
  cursor: grabbing;
}

.captcha-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
}

.captcha-done {
  margin: 6px 0 0;
  font-size: 12px;
  color: #67c23a;
}
</style>
