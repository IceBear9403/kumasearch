<script setup lang="ts">
import { ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'

import { useSuggest } from '@/composables/useSuggest'
import type { SuggestDTO } from '@/types/search'

/**
 * 带联想下拉的搜索框：
 *  - 输入防抖 200ms 后请求联想（防抖在 useSuggest 内实现）
 *  - 竞态处理由 useSuggest 保证：只采用最后一次请求的结果
 *  - 键盘上下键选择、回车确认、Esc 关闭；失焦延迟关闭以便点击选项
 */
const props = withDefaults(
  defineProps<{
    modelValue?: string
    /** 检索类型编码，联想请求需要 */
    typeCode?: string
    placeholder?: string
    size?: 'large' | 'default' | 'small'
  }>(),
  {
    modelValue: '',
    typeCode: '',
    placeholder: '请输入关键词',
    size: 'large',
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'search', value: string): void
  (e: 'select', item: SuggestDTO): void
}>()

const { suggestions, loading, suggest, clear } = useSuggest(200)

const keyword = ref(props.modelValue)
const visible = ref(false)
const activeIndex = ref(-1)

watch(
  () => props.modelValue,
  (value) => {
    if (value !== keyword.value) keyword.value = value
  },
)

function closeDropdown(): void {
  visible.value = false
  activeIndex.value = -1
}

function onInput(value: string): void {
  keyword.value = value
  emit('update:modelValue', value)
  activeIndex.value = -1

  if (!value.trim() || !props.typeCode) {
    clear()
    closeDropdown()
    return
  }
  visible.value = true
  // 防抖 200ms 后发起联想请求
  suggest(props.typeCode, value, 10)
}

function choose(item: SuggestDTO): void {
  keyword.value = item.text
  emit('update:modelValue', item.text)
  emit('select', item)
  emit('search', item.text)
  clear()
  closeDropdown()
}

function doSearch(): void {
  const value = keyword.value.trim()
  clear()
  closeDropdown()
  emit('search', value)
}

function onKeydown(event: KeyboardEvent): void {
  const list = suggestions.value

  if (event.key === 'Escape') {
    closeDropdown()
    return
  }

  // 无候选时，回车直接检索
  if (list.length === 0) {
    if (event.key === 'Enter') doSearch()
    return
  }

  switch (event.key) {
    case 'ArrowDown':
      event.preventDefault()
      visible.value = true
      activeIndex.value = (activeIndex.value + 1) % list.length
      break
    case 'ArrowUp':
      event.preventDefault()
      visible.value = true
      activeIndex.value = activeIndex.value <= 0 ? list.length - 1 : activeIndex.value - 1
      break
    case 'Enter':
      event.preventDefault()
      if (activeIndex.value >= 0 && activeIndex.value < list.length) {
        choose(list[activeIndex.value])
      } else {
        doSearch()
      }
      break
    default:
      break
  }
}

/** 失焦延迟关闭，给点击选项留出时间 */
function onBlur(): void {
  window.setTimeout(closeDropdown, 200)
}
</script>

<template>
  <div class="search-box">
    <el-input
      :model-value="keyword"
      :size="size"
      :placeholder="placeholder"
      clearable
      @input="onInput"
      @keydown="onKeydown"
      @blur="onBlur"
    >
      <template #prefix>
        <el-icon><Search /></el-icon>
      </template>
      <template #append>
        <el-button type="primary" @click="doSearch">搜索</el-button>
      </template>
    </el-input>

    <ul v-if="visible && (loading || suggestions.length > 0)" class="suggest-list">
      <li
        v-for="(item, index) in suggestions"
        :key="`${item.source}-${item.text}`"
        class="suggest-item"
        :class="{ active: index === activeIndex }"
        @mousedown.prevent="choose(item)"
      >
        <span class="suggest-text">{{ item.text }}</span>
        <el-tag size="small" type="info" effect="plain">{{ item.source }}</el-tag>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.search-box {
  position: relative;
  width: 100%;
}

.suggest-list {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  z-index: 2000;
  margin: 4px 0 0;
  padding: 4px 0;
  list-style: none;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  max-height: 320px;
  overflow-y: auto;
}

.suggest-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 14px;
}

.suggest-item.active,
.suggest-item:hover {
  background: #f5f7fa;
}

.suggest-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
