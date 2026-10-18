<script setup lang="ts">
/**
 * 分页组件，同时支持两种模式：
 *  - 页码模式：total 存在时使用 el-pagination
 *  - 游标模式：cursorMode 为 true 时只显示「上一页 / 下一页」，用 nextCursor 翻页
 *
 * 深分页场景下 total 可能为 null，此时不显示「共 N 条」。
 */
const props = withDefaults(
  defineProps<{
    /** 总条数；深分页时可能为 null */
    total?: number | null
    pageNum?: number
    pageSize?: number
    pageSizes?: number[]
    /** 是否游标分页模式 */
    cursorMode?: boolean
    /** 下一页游标 */
    nextCursor?: string | null
    /** 游标模式是否有上一页 */
    hasPrev?: boolean
    /** 是否禁用 */
    disabled?: boolean
  }>(),
  {
    total: null,
    pageNum: 1,
    pageSize: 10,
    pageSizes: () => [10, 20, 50, 100],
    cursorMode: false,
    nextCursor: null,
    hasPrev: false,
    disabled: false,
  },
)

const emit = defineEmits<{
  (e: 'update:pageNum', value: number): void
  (e: 'update:pageSize', value: number): void
  (e: 'change', payload: { pageNum: number; pageSize: number }): void
  (e: 'next'): void
  (e: 'prev'): void
}>()

function onCurrentChange(page: number): void {
  emit('update:pageNum', page)
  emit('change', { pageNum: page, pageSize: props.pageSize })
}

function onSizeChange(size: number): void {
  emit('update:pageSize', size)
  emit('change', { pageNum: 1, pageSize: size })
}
</script>

<template>
  <div class="kuma-pagination">
    <!-- 游标模式：只有上一页 / 下一页 -->
    <div v-if="cursorMode" class="cursor-pager">
      <el-button :disabled="disabled || !hasPrev" @click="emit('prev')">上一页</el-button>
      <el-button :disabled="disabled || !nextCursor" @click="emit('next')">下一页</el-button>
    </div>

    <!-- 页码模式：total 存在时才显示，避免深分页显示「共 N 条」 -->
    <el-pagination
      v-else-if="total !== null && total !== undefined"
      :current-page="pageNum"
      :page-size="pageSize"
      :page-sizes="pageSizes"
      :total="total"
      :disabled="disabled"
      background
      layout="total, sizes, prev, pager, next, jumper"
      @current-change="onCurrentChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<style scoped>
.kuma-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0;
}
</style>
