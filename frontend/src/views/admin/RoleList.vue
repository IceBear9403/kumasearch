<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import * as userApi from '@/api/admin/user'
import type { SysRole } from '@/types/admin'

/**
 * 角色列表（GET /admin/role）
 */
const loading = ref(false)
const rows = ref<SysRole[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await userApi.listRoles()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function onCreate(): void {
  // TODO: 新增/编辑角色（后端暂未提供 POST/PUT /admin/role）
  ElMessage.info('新增角色功能待后端接口支持')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
      <el-button type="success" @click="onCreate">新增角色</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="roleCode" label="角色编码" width="180" />
      <el-table-column prop="roleName" label="角色名称" width="180" />
      <el-table-column label="创建时间" min-width="200">
        <template #default="{ row }">{{ row.createTime ?? '-' }}</template>
      </el-table-column>
    </el-table>
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
</style>
