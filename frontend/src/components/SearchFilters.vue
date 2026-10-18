<script setup lang="ts">
import { computed } from 'vue'

/**
 * 已选筛选条件的 chips 展示，可单个移除或全部清空。
 */
const props = withDefaults(
  defineProps<{
    /** field -> 已选中的值列表 */
    filters?: Record<string, string[]>
    /** field -> 展示名 */
    labels?: Record<string, string>
  }>(),
  {
    filters: () => ({}),
    labels: () => ({}),
  },
)

const emit = defineEmits<{
  (e: 'remove', field: string, value: string): void
  (e: 'clear'): void
}>()

interface Chip {
  field: string
  value: string
  label: string
}

const chips = computed<Chip[]>(() => {
  const list: Chip[] = []
  Object.entries(props.filters).forEach(([field, values]) => {
    values.forEach((value) => {
      list.push({ field, value, label: props.labels[field] ?? field })
    })
  })
  return list
})
</script>

<template>
  <div v-if="chips.length > 0" class="search-filters">
    <span class="filter-title">已选条件：</span>
    <el-tag
      v-for="chip in chips"
      :key="`${chip.field}-${chip.value}`"
      class="filter-chip"
      closable
      type="primary"
      effect="light"
      @close="emit('remove', chip.field, chip.value)"
    >
      {{ chip.label }}：{{ chip.value }}
    </el-tag>
    <el-button link type="danger" @click="emit('clear')">清空</el-button>
  </div>
</template>

<style scoped>
.search-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.filter-title {
  font-size: 13px;
  color: #909399;
}

.filter-chip {
  cursor: default;
}
</style>
