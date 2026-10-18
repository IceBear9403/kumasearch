<script setup lang="ts">
import { formatNumber } from '@/utils/format'
import type { FacetDTO } from '@/types/search'

/**
 * 左侧聚合筛选面板。
 * 只负责展示与派发选中事件，具体请求由父组件/检索 store 完成。
 */
withDefaults(
  defineProps<{
    facets?: FacetDTO[]
    /** field -> 已选中的 key 列表 */
    selected?: Record<string, string[]>
    loading?: boolean
  }>(),
  {
    facets: () => [],
    selected: () => ({}),
    loading: false,
  },
)

const emit = defineEmits<{
  (e: 'toggle', field: string, key: string): void
  (e: 'clear'): void
}>()

function isChecked(selected: Record<string, string[]>, field: string, key: string): boolean {
  return (selected[field] ?? []).includes(key)
}
</script>

<template>
  <div v-loading="loading" class="facet-panel">
    <div class="facet-header">
      <span>筛选</span>
      <el-button link type="primary" size="small" @click="emit('clear')">重置</el-button>
    </div>

    <el-empty v-if="facets.length === 0" description="暂无筛选条件" :image-size="60" />

    <div v-for="facet in facets" :key="facet.field" class="facet-group">
      <div class="facet-title">
        {{ facet.label || facet.field }}
        <span class="facet-total">（{{ formatNumber(facet.total) }}）</span>
      </div>
      <el-checkbox
        v-for="bucket in facet.buckets"
        :key="bucket.key"
        :model-value="isChecked(selected, facet.field, bucket.key)"
        @change="emit('toggle', facet.field, bucket.key)"
      >
        <span class="bucket-key">{{ bucket.key }}</span>
        <!-- 后端返回的计数字段是 docCount -->
        <span class="bucket-count">{{ formatNumber(bucket.docCount) }}</span>
      </el-checkbox>
    </div>
  </div>
</template>

<style scoped>
.facet-panel {
  min-width: 200px;
}

.facet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 8px;
}

.facet-group {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f2f5;
}

.facet-title {
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}

.facet-total {
  color: #909399;
}

.bucket-key {
  margin-right: 6px;
}

.bucket-count {
  color: #909399;
  font-size: 12px;
}
</style>
