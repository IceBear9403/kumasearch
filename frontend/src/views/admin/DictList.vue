<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as dictApi from '@/api/admin/dict'
import type { DictType, SearchDict } from '@/types/admin'

/**
 * 数据字典（词典）：字段对齐后端 t_search_dict
 */
const loading = ref(false)
const rows = ref<SearchDict[]>([])
const dictType = ref('')

/** 词典类型选项 */
const dictTypeOptions: DictType[] = ['MAIN', 'STOP', 'SYNONYM', 'SENSITIVE']

/**
 * 词典类型的中文说明。
 *
 * 直接在选项里显示类型名（MAIN / STOP / SYNONYM / SENSITIVE）是不够的 ——
 * 这四类的作用完全不同，而其中**只有 SENSITIVE 会决定内容能不能对外可见**：
 * 在它里面加一个词，命中的采集内容会被直接驳回。
 * 让维护词库的人先看到这行说明，比事后解释"为什么一批内容突然被驳回了"便宜得多。
 */
const dictTypeHints: Record<string, string> = {
  MAIN: '主词：影响检索与排序的常用词',
  STOP: '停用词：检索时忽略的高频虚词',
  SYNONYM: '同义词：扩展检索命中范围',
  SENSITIVE: '敏感词：命中即驳回采集内容，维护需谨慎',
}

/* 新增弹窗 */
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<SearchDict>({
  dictType: 'MAIN',
  word: '',
  synonyms: '',
  status: 1,
})

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await dictApi.listDicts({ dictType: dictType.value || undefined })
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.dictType = dictType.value || 'MAIN'
  form.word = ''
  form.synonyms = ''
  form.status = 1
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.dictType || !form.word) {
    ElMessage.warning('词典类型、词条均为必填')
    return
  }
  saving.value = true
  try {
    await dictApi.createDict({ ...form })
    ElMessage.success('新增成功')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row: SearchDict): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(`确认删除词条「${row.word}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await dictApi.removeDict(row.id)
  ElMessage.success('删除成功')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-select
        v-model="dictType"
        placeholder="词典类型（dictType）"
        clearable
        style="width: 220px"
        @change="load"
      >
        <el-option v-for="type in dictTypeOptions" :key="type" :label="type" :value="type" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="openCreate">新增词条</el-button>
    </div>

    <el-alert
      v-if="dictType === 'SENSITIVE'"
      type="warning"
      :closable="false"
      show-icon
      class="sensitive-tip"
    >
      <template #title>敏感词决定「哪条采集内容能对外可见」，与其它三类词典有本质区别</template>
      其它三类改错了只是排得不好；这一类误加一个词，会让一批正常内容被直接驳回。
      加词前请先想清楚它在正常语境里会不会出现（例如「赌博」出现在一篇介绍反赌博的新闻里
      是完全正常的）。本地词库只能挡住已知的违规内容，它是第一道闸而不是唯一一道。
    </el-alert>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="词典类型" width="220">
        <template #default="{ row }">
          <el-tag :type="row.dictType === 'SENSITIVE' ? 'danger' : 'info'" size="small">
            {{ row.dictType }}
          </el-tag>
          <span class="type-hint">{{ dictTypeHints[row.dictType] || '' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="word" label="词条" width="200" />
      <el-table-column prop="synonyms" label="同义词" min-width="200" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增词条" width="440px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="词典类型">
          <el-select v-model="form.dictType" style="width: 100%">
            <el-option
              v-for="type in dictTypeOptions"
              :key="type"
              :label="type"
              :value="type"
            />
          </el-select>
          <div class="field-hint">{{ dictTypeHints[form.dictType] || '' }}</div>
        </el-form-item>
        <el-form-item label="词条">
          <el-input v-model="form.word" />
        </el-form-item>
        <el-form-item label="同义词">
          <el-input v-model="form.synonyms" type="textarea" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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

.sensitive-tip {
  margin-bottom: 12px;
}

.type-hint {
  margin-left: 6px;
  font-size: 12px;
  color: #909399;
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
