<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import DynamicContentForm from '@/components/DynamicContentForm.vue'
import Pagination from '@/components/Pagination.vue'
import * as contentApi from '@/api/admin/content'
import type { ContentStatus, ContentTypeSchema, SearchContent } from '@/types/admin'
import { formatDateTime } from '@/utils/format'
import { contentStatusLabel, contentStatusTagType } from '@/utils/content'

/**
 * 内容维护页。
 *
 * <h3>这一页存在的理由</h3>
 * 在此之前，`t_search_content` 只能靠采集或直接写库填充 ——「新增内容 / 编辑」两个按钮
 * 都指向同一个只弹提示的空函数。于是一个全新部署的站点搜不出任何东西，而这不是 bug，
 * 是没有入口。
 *
 * <h3>表单字段是**动态**的，不能写死</h3>
 * 字段清单在 `t_index_type_config.field_config` 里，是配置。这里不写死任何字段名，
 * 一切都交给 `DynamicContentForm`（它按 `GET /admin/content/schema` 渲染）。
 * 加一个字段不该改前端代码。
 *
 * <h3>「放行 / 驳回」就是改 status 再保存</h3>
 * 没有单独的审核端点。投稿（前台）与采集内容都落 `status=0` 待审，
 * 在这一页把状态改成 1 或 2 再提交即可 —— 与用户投稿共用同一条链路，
 * 因此不存在"审核走的是另一套代码"这种分叉。
 */

const loading = ref(false)
const rows = ref<SearchContent[]>([])
const total = ref(0)

/** 所有启用类型的表单结构。列表页要用它来做类型下拉与"这个类型能不能投稿"的提示 */
const schemas = ref<ContentTypeSchema[]>([])

const query = reactive<{
  pageNum: number
  pageSize: number
  typeCode: string
  keyword: string
  status: ContentStatus | ''
}>({
  pageNum: 1,
  pageSize: 10,
  typeCode: '',
  keyword: '',
  status: '',
})

/* ------------------------------ 抽屉表单 ------------------------------ */

const drawerVisible = ref(false)
const saving = ref(false)
/** 编辑中的原行；为 null 表示新增 */
const editingRow = ref<SearchContent | null>(null)
/** 当前表单对应的类型编码。新增时由用户先选，编辑时锁定为原行的类型 */
const formTypeCode = ref('')
const formRef = ref<InstanceType<typeof DynamicContentForm>>()

const currentSchema = computed<ContentTypeSchema | null>(
  () => schemas.value.find((item) => item.typeCode === formTypeCode.value) ?? null,
)

async function loadSchemas(): Promise<void> {
  try {
    schemas.value = await contentApi.listContentSchemas()
  } catch {
    // 拿不到结构不影响看列表与审核（那两件事只用得到行里的 status），
    // 只是"新增/编辑"会没有字段可渲染。因此降级为空数组而不是让整页报错。
    schemas.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await contentApi.listContent({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      typeCode: query.typeCode || undefined,
      keyword: query.keyword || undefined,
      // 空字符串表示"全部"：必须转成 undefined，否则会被序列化成 status= 这种空参数
      status: query.status === '' ? undefined : query.status,
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

/* ------------------------------ 新增 / 编辑 ------------------------------ */

/**
 * 剥掉后端自己维护的列再提交。
 *
 * `id` / `createTime` / `updateTime` / `deleted` 由服务端与 MyBatis-Plus 负责，
 * 把从列表里读到的旧值原样回传没有意义，还会在 `updateById` 时把审计列写回去。
 */
function stripAudit(row: SearchContent): Omit<SearchContent, 'id' | 'createTime' | 'updateTime' | 'deleted'> {
  const { id, createTime, updateTime, deleted, ...rest } = row
  return rest
}

/**
 * 合并"原行 + 表单值"。
 *
 * ⚠️ 编辑时**不能**直接用表单的输出替换整行。表单只认识 field_config 里声明过的字段，
 * 而一行内容在库里可能有别的列（`webpage` 就没声明 `categoryId` / `authorId` / `price`），
 * 直接替换会把它们抹成空。以原行为底、用表单的值覆盖，才是"编辑"该有的语义。
 */
function mergeWithRow(row: SearchContent | null, built: SearchContent): SearchContent {
  if (!row) return built
  return { ...stripAudit(row), ...built }
}

async function openCreate(): Promise<void> {
  editingRow.value = null
  formTypeCode.value = ''
  drawerVisible.value = true
  await nextTick()
  // 显式 loadFrom(null)：连续两次新增同一个类型时 typeCode 没变，组件的
  // schema watcher 不会触发，不清一次就会看到上一次残留的值。
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
    ElMessage.warning('请先选择内容类型')
    return
  }
  if (!formRef.value) return
  if (!(await formRef.value.validate())) return

  const built = formRef.value.buildContent()
  if (!built) return

  const payload = mergeWithRow(editingRow.value, built)
  if (payload.status === undefined) {
    // 后端对缺失的 status 默认置 1（已发布）。这里显式写出来而不是依赖那个默认值：
    // 让一条内容"立刻对外可见"的决定不该藏在一层网络调用的默认参数里。
    payload.status = editingRow.value?.status ?? 1
  }

  saving.value = true
  try {
    await contentApi.createContent(payload)
    // 提示里必须说清"约 1 秒后才搜得到"：写入走 Outbox 异步投递，
    // 不然用户保存完立刻去搜、搜不到，会以为保存失败了
    ElMessage.success('已保存。索引是异步同步的，约 1 秒后可搜到。')
    drawerVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 放行 / 驳回 ------------------------------ */

/**
 * 改状态。
 *
 * 就是"取出这一行、把 status 改掉、整行提交"，没有额外的审核端点 ——
 * 审核与录入写的是同一条链路，因此不会出现"后台审核的结果与手工编辑的结果不一致"。
 */
async function changeStatus(row: SearchContent, status: ContentStatus): Promise<void> {
  const action = contentStatusLabel(status)
  try {
    await ElMessageBox.confirm(
      status === 1
        ? `放行后「${row.title || row.bizId}」将进入检索结果，约 1 秒后对外可见。`
        : `驳回后「${row.title || row.bizId}」不会被搜到。若这是用户投稿，` +
            '请先在编辑里填写驳回原因（写入 extra.rejectReason），投稿人能在「我的投稿」看到它。',
      action,
      { type: status === 1 ? 'info' : 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await contentApi.createContent({ ...stripAudit(row), status })
  ElMessage.success(`已${action}`)
  await load()
}

async function onDelete(row: SearchContent): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将删除内容「${row.title || row.bizId}」（${row.typeCode}）。` +
        '这是逻辑删除：数据库里仍保留这一行，但检索结果中约 1 秒后消失。',
      '删除内容',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await contentApi.removeContent(row.typeCode, row.bizId)
  ElMessage.success('已删除')
  await load()
}

onMounted(async () => {
  await loadSchemas()
  await load()
})
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>搜索搜不到内容，先看这里</template>
      内容要先<b>落库</b>、再经 Outbox <b>异步</b>同步进 ES，最后还要求 <code>status=1</code>
      才会出现在检索结果里。状态为「待审」的内容<b>已经在库里、也已经进了索引</b>，
      只是查询时被过滤掉 —— 所以它搜不到不是没同步，是还没放行。
    </el-alert>

    <div class="toolbar">
      <el-select
        v-model="query.typeCode"
        placeholder="全部类型"
        clearable
        style="width: 180px"
        @change="onSearch"
      >
        <el-option
          v-for="item in schemas"
          :key="item.typeCode"
          :label="`${item.typeName}（${item.typeCode}）`"
          :value="item.typeCode"
        />
      </el-select>

      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 140px"
        @change="onSearch"
      >
        <el-option label="待审" :value="0" />
        <el-option label="已发布" :value="1" />
        <el-option label="已驳回" :value="2" />
      </el-select>

      <el-input
        v-model="query.keyword"
        placeholder="标题关键词"
        clearable
        style="width: 200px"
        @keyup.enter="onSearch"
      />
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button type="success" @click="openCreate">新增内容</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="typeCode" label="类型" width="110" />
      <el-table-column prop="bizId" label="业务 ID" width="190" show-overflow-tooltip />
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.title || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="contentStatusTagType(row.status)" size="small">
            {{ contentStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="authorId" label="作者" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.authorId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="bizWeight" label="权重" width="80" />
      <el-table-column label="发布时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.publishTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="row.status !== 1"
            link
            type="success"
            @click="changeStatus(row, 1)"
          >
            放行
          </el-button>
          <el-button
            v-if="row.status !== 2"
            link
            type="warning"
            @click="changeStatus(row, 2)"
          >
            驳回
          </el-button>
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

    <!--
      刻意**不用** destroy-on-close：它会在每次打开时重建 DynamicContentForm，
      而重建的时机与下面 `await nextTick()` 里那次 `loadFrom` 谁先谁后并不确定 ——
      晚了就是"点编辑打开一个空表单"。清空由显式的 `loadFrom(null)` 负责。
    -->
    <el-drawer
      v-model="drawerVisible"
      :title="editingRow ? '编辑内容' : '新增内容'"
      size="720px"
    >
      <el-form label-width="110px">
        <el-form-item label="内容类型" required>
          <el-select
            v-model="formTypeCode"
            :disabled="!!editingRow"
            placeholder="请选择要录入的类型"
            style="width: 100%"
          >
            <el-option
              v-for="item in schemas"
              :key="item.typeCode"
              :label="`${item.typeName}（${item.typeCode}）`"
              :value="item.typeCode"
            />
          </el-select>
          <div class="field-hint">
            <template v-if="editingRow">
              类型不可修改：它决定了这份内容落在哪个 ES 索引、有哪些字段。
              要换类型请新建一条。
            </template>
            <template v-else>
              表单字段随所选类型变化。类型 = 一份字段配置 = 一个 ES 索引
              （<code>kuma_&lt;typeCode&gt;</code>）。
            </template>
          </div>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="currentSchema && currentSchema.allowUserSubmit"
        type="warning"
        :closable="false"
        show-icon
        class="tip"
      >
        该类型<b>对前台用户开放投稿</b>。用户投的稿子会落「待审」，需要在这一页放行才可被搜到。
      </el-alert>

      <DynamicContentForm
        ref="formRef"
        :schema="currentSchema"
        mode="admin"
        :disabled="saving"
      />

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}

.tip {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.field-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}
</style>
