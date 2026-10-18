<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as syncTaskApi from '@/api/admin/syncTask'
import { formatDateTime } from '@/utils/format'
import type { SyncTask } from '@/types/admin'

/**
 * 同步任务列表。
 *
 * 后端 `GET /admin/sync-task` 返回数组（非分页），因此过滤在客户端完成。
 */
const loading = ref(false)
const rows = ref<SyncTask[]>([])
const typeCode = ref('')
const status = ref('')

/** 客户端过滤：按类型编码 / 状态 */
const filteredRows = computed(() => {
  const kw = typeCode.value.trim().toLowerCase()
  const st = status.value === '' ? null : Number(status.value)
  return rows.value.filter((row) => {
    const matchType = !kw || (row.typeCode ?? '').toLowerCase().includes(kw)
    const matchStatus = st === null || row.status === st
    return matchType && matchStatus
  })
})

/** 状态 -> tag 类型（后端 status 为 1 启用 / 0 停用） */
function statusTagType(value?: number): 'success' | 'info' {
  return value === 1 ? 'success' : 'info'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await syncTaskApi.listSyncTasks()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  // 过滤在客户端完成，这里仅作为“查询”按钮的触发点
  typeCode.value = typeCode.value.trim()
}

async function onRun(row: SyncTask): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(`确认立即执行任务「${row.taskName}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await syncTaskApi.runSyncTask(row.id)
  ElMessage.success('任务已触发')
  await load()
}

async function onDelete(row: SyncTask): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(`确认删除任务「${row.taskName}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await syncTaskApi.removeSyncTask(row.id)
  ElMessage.success('删除成功')
  await load()
}

function onCreate(): void {
  // TODO: 新增/编辑同步任务弹窗（taskName、typeCode、taskType、cronExpr）
  ElMessage.info('新增同步任务功能待实现')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="typeCode" placeholder="类型编码" clearable style="width: 160px" />
      <el-select v-model="status" placeholder="状态" clearable style="width: 140px">
        <el-option label="启用" value="1" />
        <el-option label="停用" value="0" />
      </el-select>
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button type="success" @click="onCreate">新增任务</el-button>
    </div>

    <el-table v-loading="loading" :data="filteredRows" border stripe>
      <el-table-column prop="taskName" label="任务名称" min-width="160" />
      <el-table-column prop="typeCode" label="类型编码" width="130" />
      <el-table-column prop="taskType" label="任务类型" width="120" />
      <el-table-column prop="cronExpr" label="cron 表达式" width="150" />
      <el-table-column prop="batchSize" label="批量大小" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上次执行" width="180">
        <template #default="{ row }">{{ formatDateTime(row.lastRunTime) }}</template>
      </el-table-column>
      <el-table-column prop="lastResult" label="上次结果" min-width="140" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onRun(row)">执行</el-button>
          <el-button link type="primary" @click="onCreate">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="table-footer">
      <span>共 {{ filteredRows.length }} 条</span>
      <el-button link type="primary" :loading="loading" @click="load">刷新</el-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  color: #909399;
  font-size: 13px;
  margin-top: 12px;
}
</style>
