<script setup lang="ts">
import { computed } from 'vue'

import { parseHighlight, type HighlightSegment } from '@/utils/highlight'

/**
 * 安全渲染高亮片段。
 *
 * **安全契约**：本组件只通过插值语法 `{{ }}` 渲染文本，绝不使用任何 HTML 注入指令。
 * 片段内容来自文档正文（用户数据），可能含 <script> 之类的字符 ——
 * 插值会把它们渲染成文本节点而不是标签，这是本组件唯一的防线。
 *
 * 与之配套的是 `parseHighlight` **不做** HTML 转义（转义由插值负责），
 * 两者必须同时成立：若这里改成注入指令，未转义的文本就会变成 XSS；
 * 若那里改回转义，页面上会出现双重转义后的 "&amp;"。改动任一侧前请先读
 * `@/utils/highlight` 的文件头说明。
 */
const props = withDefaults(
  defineProps<{
    /** 带 <em> 标签的高亮片段 */
    content?: string
  }>(),
  { content: '' },
)

const segments = computed<HighlightSegment[]>(() => parseHighlight(props.content))
</script>

<template>
  <span class="kuma-highlight-text">
    <template v-for="(seg, index) in segments" :key="index">
      <span v-if="seg.highlight" class="kuma-highlight">{{ seg.text }}</span>
      <template v-else>{{ seg.text }}</template>
    </template>
  </span>
</template>

<style scoped>
.kuma-highlight-text {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
