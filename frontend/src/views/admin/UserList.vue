<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import Pagination from '@/components/Pagination.vue'
import * as userApi from '@/api/admin/user'
import type { SysRole, SysUser } from '@/types/admin'

/**
 * 用户与角色管理
 */
const loading = ref(false)
const rows = ref<SysUser[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '' })

/* 分配角色弹窗 */
const roleDialogVisible = ref(false)
const roleSaving = ref(false)
const allRoles = ref<SysRole[]>([])
const currentUser = ref<SysUser | null>(null)
const selectedRoleIds = ref<number[]>([])

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await userApi.listUsers({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
    })
    rows.value = page.records ?? []
    total.value = page.total ?? 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  query.pageNum = 1
  void load()
}

function onPageChange(payload: { pageNum: number; pageSize: number }): void {
  query.pageNum = payload.pageNum
  query.pageSize = payload.pageSize
  void load()
}

async function openRoleDialog(row: SysUser): Promise<void> {
  if (row.id === undefined) return
  currentUser.value = row
  roleDialogVisible.value = true
  if (allRoles.value.length === 0) {
    allRoles.value = await userApi.listRoles()
  }
  // 后端返回角色对象数组，这里取出 ID 供 checkbox 绑定
  const roles = await userApi.getUserRoles(row.id)
  selectedRoleIds.value = roles.map((role) => role.id)
}

async function saveRoles(): Promise<void> {
  const userId = currentUser.value?.id
  if (userId === undefined) return
  roleSaving.value = true
  try {
    await userApi.assignUserRoles(userId, selectedRoleIds.value)
    ElMessage.success('角色已更新')
    roleDialogVisible.value = false
    await load()
  } finally {
    roleSaving.value = false
  }
}

async function onDelete(row: SysUser): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(`确认删除用户「${row.username}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await userApi.removeUser(row.id)
  ElMessage.success('删除成功')
  await load()
}

function onCreate(): void {
  // TODO: 新增/编辑用户弹窗
  ElMessage.info('新增用户功能待实现')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="用户名 / 昵称"
        clearable
        style="width: 200px"
        @keyup.enter="onSearch"
      />
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button type="success" @click="onCreate">新增用户</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="username" label="用户名" width="160" />
      <el-table-column prop="nickname" label="昵称" width="160" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRoleDialog(row)">分配角色</el-button>
          <el-button link type="primary" @click="onCreate">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <Pagination
      :total="total"
      :page-num="query.pageNum"
      :page-size="query.pageSize"
      :disabled="loading"
      @change="onPageChange"
    />

    <!-- 分配角色 -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="420px">
      <el-checkbox-group v-model="selectedRoleIds">
        <el-checkbox v-for="role in allRoles" :key="role.id" :value="role.id">
          {{ role.roleName }}（{{ role.roleCode }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>
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

.role-tag {
  margin-right: 4px;
}
</style>
