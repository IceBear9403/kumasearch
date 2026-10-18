<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import DynamicContentForm from '@/components/DynamicContentForm.vue'
import Pagination from '@/components/Pagination.vue'
import * as contentApi from '@/api/content'
import type { ContentStatus, ContentTypeSchema, SearchContent } from '@/types/admin'
import { formatDateTime } from '@/utils/format'
import { contentStatusLabel, contentStatusTagType, readExtraKey } from '@/utils/content'

/**
 * 我的投稿。
 *
 * <h3>这一页要对抗的最大误解：「我提交了，所以大家能搜到了」</h3>
 * 投出去的稿子一律落**待审**，管理员在后台放行之后才会进检索结果。
 * 因此状态列与那句说明不是装饰 —— 没有它们，用户会反复刷新搜索页，
 * 然后认为"投稿功能坏了"。所以：
 * - 待审的行明确写「待审：审核通过后才可被搜到」；
 * - 已驳回的行直接展示 `extra.rejectReason`（那是管理员写的理由）；
 * - 已发布的行**不给**编辑与撤回按钮，并说明原因（要改请联系管理员先下架）。
 *
 * <h3>为什么已发布的不给改</h3>
 * 允许改就等于允许「先投一篇正常的过审、再改成任意内容」，整个审核队列形同虚设。
 * 这条规则由服务端强制（改已发布的返回 `20006`），前端藏按钮只是不去撞它。
 */
const loading = ref(false)
const rows = ref<SearchContent[]>([])
const total = ref(0)

/** 可投稿的类型结构。只有「已启用且允许投稿」的类型才会出现在这里 */
const schemas = ref<ContentTypeSchema[]>([])
const schemasLoaded = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  typeCode: '',
})

/* ------------------------------ 抽屉表单 ------------------------------ */

const drawerVisible = ref(false)
const saving = ref(false)
/** 编辑中的原行；为 null 表示新投稿 */
const editingRow = ref<SearchContent | null>(null)
const formTypeCode = ref('')
const formRef = ref<InstanceType<typeof DynamicContentForm>>()

const currentSchema = computed<ContentTypeSchema | null>(
  () => schemas.value.find((item) => item.typeCode === formTypeCode.value) ?? null,
)

async function loadSchemas(): Promise<void> {
  try {
    schemas.value = await contentApi.listSubmittableSchemas()
  } catch {
    schemas.value = []
  } finally {
    schemasLoaded.value = true
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await contentApi.mySubmissions({
      typeCode: query.typeCode || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize,
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

/* ------------------------------ 新建 / 编辑 ------------------------------ */

async function openCreate(): Promise<void> {
  editingRow.value = null
  // 只有一个可投稿类型时直接选中：少一次没有信息量的选择
  formTypeCode.value = schemas.value.length === 1 ? schemas.value[0]!.typeCode : ''
  drawerVisible.value = true
  await nextTick()
  formRef.value?.loadFrom(null)
}

async function openEdit(row: SearchContent): Promise<void> {
  editingRow.value = row
  formTypeCode.value = row.typeCode
  drawerVisible.value = true
  await nextTick()
  formRef.value?.loadFrom(row)
}

async function save(): Promise<void> {
  if (!formTypeCode.value) {
    ElMessage.warning('请先选择要投稿的类型')
    return
  }
  if (!formRef.value) return
  if (!(await formRef.value.validate())) return

  const built = formRef.value.buildContent()
  if (!built) return

  saving.value = true
  try {
    if (editingRow.value) {
      // 修改必须带上 bizId：服务端靠 typeCode + bizId 定位并校验归属，
      // 而 bizId 是保留字段、不在表单里，所以只能从原行取。
      await contentApi.updateSubmission({
        ...built,
        typeCode: editingRow.value.typeCode,
        bizId: editingRow.value.bizId,
      })
      ElMessage.success('已重新提交，稿件回到「待审」，需要重新审核。')
    } else {
      await contentApi.submitContent(built)
      ElMessage.success('已提交。稿件处于「待审」，管理员放行后才会出现在搜索结果里。')
    }
    drawerVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 撤回 ------------------------------ */

async function onWithdraw(row: SearchContent): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将撤回投稿「${row.title || row.bizId}」。撤回后它不再出现在这个列表里（约 1 秒后从检索结果消失），` +
        '但可以重新投一次。原记录的删除会留痕，不会被覆盖。',
      '撤回投稿',
      { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await contentApi.withdrawSubmission(row.typeCode, row.bizId)
  ElMessage.success('已撤回')
  await load()
}

/* ------------------------------ 展示辅助 ------------------------------ */

/** 已发布的行不能自助修改或撤回，见文件头 */
function isEditable(status?: ContentStatus): boolean {
  return status !== 1
}

/** 驳回原因。管理员写在 `extra.rejectReason` 里；没有就退回显示命中的敏感词 */
function rejectReason(row: SearchContent): string {
  if (row.status !== 2) return ''
  const reason = readExtraKey(row.extra, 'rejectReason')
  if (reason) return reason
  const matched = readExtraKey(row.extra, 'matchedWords')
  return matched ? `命中敏感词：${matched}` : '未填写驳回原因'
}

onMounted(async () => {
  await loadSchemas()
  await load()
})
</script>

<template>
  <div class="submit-page">
    <div class="page-header">
      <h2 class="page-title">我的投稿</h2>
      <el-button type="primary" :disabled="schemas.length === 0" @click="openCreate">
        我要投稿
      </el-button>
    </div>

    <!-- 一个类型都没开放时，把原因说清楚：这不是页面出错 -->
    <el-alert
      v-if="schemasLoaded && schemas.length === 0"
      type="info"
      :closable="false"
      show-icon
      class="tip"
    >
      <template #title>当前没有开放投稿的内容类型</template>
      投稿是逐类型开放的。本站还没有把任何类型设为「允许前台投稿」，因此暂时无法投稿 ——
      请联系管理员在「索引类型」里打开对应类型的投稿开关。
    </el-alert>

    <el-alert v-else type="info" :closable="false" show-icon class="tip">
      <template #title>投稿要经人工审核后才可被搜到</template>
      提交后稿件的状态是<b>「待审」</b>：它已经保存下来了，但还<b>不会</b>出现在搜索结果里。
      管理员放行后才可被搜到；被驳回的稿件会在这里显示理由，可以改完再投一次。
    </el-alert>

    <div class="toolbar">
      <el-select
        v-model="query.typeCode"
        placeholder="全部类型"
        clearable
        style="width: 200px"
        @change="onSearch"
      >
        <el-option
          v-for="item in schemas"
          :key="item.typeCode"
          :label="`${item.typeName}（${item.typeCode}）`"
          :value="item.typeCode"
        />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.title || '（无标题）' }}</template>
      </el-table-column>
      <el-table-column prop="typeCode" label="类型" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="contentStatusTagType(row.status)" size="small">
            {{ contentStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="200">
        <template #default="{ row }">
          <span v-if="row.status === 1" class="muted">已发布，可在搜索结果中看到</span>
          <span v-else-if="row.status === 2" class="danger">{{ rejectReason(row) }}</span>
          <span v-else class="muted">等待管理员审核，通过后才可被搜到</span>
        </template>
      </el-table-column>
      <el-table-column prop="bizId" label="投稿编号" width="170" show-overflow-tooltip />
      <el-table-column label="最后更新" width="170">
        <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="!isEditable(row.status)"
            @click="openEdit(row)"
          >
            编辑
          </el-button>
          <el-button
            link
            type="danger"
            :disabled="!isEditable(row.status)"
            @click="onWithdraw(row)"
          >
            撤回
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="!loading && rows.length === 0"
      description="还没有投过稿"
      :image-size="80"
    />

    <Pagination
      :total="total"
      :page-num="query.pageNum"
      :page-size="query.pageSize"
      :disabled="loading"
      @change="onPageChange"
    />

    <el-drawer
      v-model="drawerVisible"
      :title="editingRow ? '修改投稿' : '我要投稿'"
      size="680px"
    >
      <el-form label-width="100px">
        <el-form-item label="内容类型" required>
          <el-select
            v-model="formTypeCode"
            :disabled="!!editingRow"
            placeholder="请选择类型"
            style="width: 100%"
          >
            <el-option
              v-for="item in schemas"
              :key="item.typeCode"
              :label="`${item.typeName}（${item.typeCode}）`"
              :value="item.typeCode"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <el-alert type="warning" :closable="false" show-icon class="tip">
        稿件提交后处于「待审」。<b>改过或撤回后重新提交的，都要重新审核。</b>
      </el-alert>

      <DynamicContentForm ref="formRef" :schema="currentSchema" mode="submit" :disabled="saving" />

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editingRow ? '重新提交' : '提交' }}
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.submit-page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.tip {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.muted {
  color: #909399;
  font-size: 13px;
}

.danger {
  color: #f56c6c;
  font-size: 13px;
}
</style>
