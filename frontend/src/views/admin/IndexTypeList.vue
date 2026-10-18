<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import FieldConfigEditor from '@/components/FieldConfigEditor.vue'
import * as indexTypeApi from '@/api/admin/indexType'
import type { IndexStatus, IndexTypeConfig } from '@/types/admin'

/**
 * 索引类型管理：配置的增删改 + 索引的运维操作。
 *
 * <h3>这一页要先回答「typeCode 到底是什么」</h3>
 * 在此之前，列表里只有 typeCode 与一串操作按钮，没有任何地方解释它凭什么存在，
 * 也没有"新增 / 编辑 / 删除配置"的入口 —— 于是「增减检索类型」这件事在界面上
 * 根本无路可走（`api/admin/indexType.ts` 里 12 个函数一个调用点都没有）。
 * 页面顶部的说明与对话框里的提示就是补这个洞，解释写在**用户要动手的地方**，
 * 而不是只写在文档里。
 *
 * <h3>配置 与 索引 是两件事，操作也分成两组</h3>
 * - **配置**（这张表）= 这个类型有哪些字段、怎么索引、谁能投稿。改配置**不重建索引不会生效**；
 * - **索引**（ES 里的东西）= 按配置建出来的物理结构。建/删/重建都在这里。
 * 把它们混在一列按钮里，会让人以为"保存配置"就等于"改好了"。
 */
const loading = ref(false)
const rows = ref<IndexTypeConfig[]>([])
const keyword = ref('')

/** 客户端关键字过滤（后端列表接口不接受 keyword 参数） */
const filteredRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return rows.value
  return rows.value.filter(
    (row) => row.typeCode.toLowerCase().includes(kw) || row.typeName.toLowerCase().includes(kw),
  )
})

/* ------------------------------ mapping 预览 ------------------------------ */

const drawerVisible = ref(false)
const drawerLoading = ref(false)
/** 后端返回的是 mapping JSON 字符串 */
const mappingRaw = ref('')
const previewType = ref('')

/** 把 mapping JSON 字符串格式化为缩进后的文本用于展示 */
const mappingPretty = computed(() => {
  if (!mappingRaw.value) return ''
  try {
    return JSON.stringify(JSON.parse(mappingRaw.value), null, 2)
  } catch {
    return mappingRaw.value
  }
})

/* ------------------------------ 索引状态 ------------------------------ */

const statusDialogVisible = ref(false)
const status = ref<IndexStatus | null>(null)

/**
 * 索引是否真的齐了。
 *
 * ⚠️ 不能只看 `exists`：那**只是主索引**。联想索引是另一个物理索引，
 * 「主索引在、联想索引缺」是一种真实存在的半成品状态，此时检索本身能用，
 * 但每次击键都会以一条 `no such index` 的 WARN 收场。
 */
function isIndexHealthy(value: IndexStatus | null): boolean {
  if (!value) return false
  return Boolean(value.exists) && (!value.suggestRequired || Boolean(value.suggestExists))
}

/* ------------------------------ 类型配置表单 ------------------------------ */

const dialogVisible = ref(false)
const saving = ref(false)
/** 编辑时的行；为 null 表示新增 */
const editingRow = ref<IndexTypeConfig | null>(null)
const formRef = ref<FormInstance>()
const editorRef = ref<InstanceType<typeof FieldConfigEditor>>()

interface TypeForm {
  typeCode: string
  typeName: string
  indexAlias: string
  shards: number
  replicas: number
  pkField: string
  status: number
  /** 是否允许前台用户投稿：1 允许 / 0 不允许 */
  allowUserSubmit: number
}

const form = reactive<TypeForm>({
  typeCode: '',
  typeName: '',
  indexAlias: '',
  shards: 3,
  replicas: 0,
  pkField: 'id',
  status: 1,
  allowUserSubmit: 0,
})

const rules: FormRules = {
  typeCode: [
    { required: true, message: '请输入类型编码', trigger: 'blur' },
    {
      // 与后端 validateTypeCode 同一份规则：它会被拼进 ES 的索引名，
      // 而 ES 的索引名只允许小写字母、数字与 _ - + .
      pattern: /^[a-z][a-z0-9_]{0,62}$/,
      message: '只能用小写字母、数字与下划线，必须以字母开头，最长 63 字符',
      trigger: 'blur',
    },
  ],
  typeName: [{ required: true, message: '请输入类型名称', trigger: 'blur' }],
}

const isEditing = computed(() => editingRow.value !== null)

async function load(): Promise<void> {
  loading.value = true
  try {
    rows.value = await indexTypeApi.listIndexTypes()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  // 关键字过滤在客户端完成，这里仅作为“查询”按钮的触发点
  keyword.value = keyword.value.trim()
}

async function openCreate(): Promise<void> {
  editingRow.value = null
  Object.assign(form, {
    typeCode: '',
    typeName: '',
    indexAlias: '',
    shards: 3,
    replicas: 0,
    pkField: 'id',
    status: 1,
    // 新建一律默认关闭投稿：它是一个"对任何登录用户开放写入"的入口，
    // 漏配一次的代价是公开的垃圾内容，而不是少一个功能。
    allowUserSubmit: 0,
  })
  dialogVisible.value = true
  await nextTick()
  // 新建时给一份够用的起始结构。空表格会让人以为"这里没法配"，
  // 而实际上他需要至少填一个字段才能保存 —— 与其报错，不如先放一个常见字段的模板。
  editorRef.value?.loadFrom(
    JSON.stringify([
      { name: 'title', label: '标题', type: 'TEXT', searchable: true, highlight: true },
      { name: 'content', label: '正文', type: 'TEXT', searchable: true, highlight: true },
    ]),
  )
}

async function openEdit(row: IndexTypeConfig): Promise<void> {
  editingRow.value = row
  Object.assign(form, {
    typeCode: row.typeCode,
    typeName: row.typeName ?? '',
    indexAlias: row.indexAlias ?? '',
    shards: row.shards ?? 3,
    replicas: row.replicas ?? 0,
    pkField: row.pkField ?? 'id',
    status: row.status === 0 ? 0 : 1,
    allowUserSubmit: row.allowUserSubmit === 1 ? 1 : 0,
  })
  dialogVisible.value = true
  await nextTick()
  editorRef.value?.loadFrom(row.fieldConfig)
}

async function save(): Promise<void> {
  if (!formRef.value || !editorRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  // 字段配置的校验放在元数据之后：它的报错更长、更需要被看到，
  // 若先报元数据的错，用户改完再点保存时才会看到第二类错，来回两趟。
  if (!(await editorRef.value.validate())) return

  const fieldConfig = editorRef.value.buildJson()
  if (fieldConfig === null) return

  saving.value = true
  try {
    if (editingRow.value) {
      // 编辑只提交可改的字段。typeCode / indexAlias / currentVersion 是索引身份的一部分，
      // 后端也会把它们置空忽略 —— 这里干脆不发，免得后来的人以为发过去是有效的。
      await indexTypeApi.updateIndexType({
        id: editingRow.value.id,
        typeName: form.typeName,
        shards: form.shards,
        replicas: form.replicas,
        pkField: form.pkField,
        status: form.status,
        allowUserSubmit: form.allowUserSubmit,
        fieldConfig,
      })
      ElMessage.success(
        '配置已保存。⚠️ 字段配置的改动要**重建索引**才对存量数据生效，检索行为也不会立刻改变。',
      )
    } else {
      await indexTypeApi.createIndexType({
        typeCode: form.typeCode.trim(),
        typeName: form.typeName,
        // 留空发空串，由服务端回落成 kuma_<typeCode>（它认的是 isBlank，不是 null）
        indexAlias: form.indexAlias.trim(),
        shards: form.shards,
        replicas: form.replicas,
        pkField: form.pkField,
        status: form.status,
        allowUserSubmit: form.allowUserSubmit,
        fieldConfig,
      })
      // 说清"配置建好了，索引还没建"：这两步之间隔着一个必须显式点的按钮，
      // 不说的话用户会直接去录内容，然后奇怪为什么搜不到
      ElMessage.success('类型已创建。还需要对它执行一次「建索引」，之后内容才能被检索到。')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

/* ------------------------------ 索引运维操作 ------------------------------ */

type RowCommand =
  | 'mapping'
  | 'status'
  | 'create'
  | 'rebuild'
  | 'rebuildSuggest'
  | 'analyzers'
  | 'deleteIndex'

function onRowCommand(command: string, row: IndexTypeConfig): void {
  const cmd = command as RowCommand
  switch (cmd) {
    case 'mapping':
      void onPreviewMapping(row)
      return
    case 'status':
      void onShowStatus(row)
      return
    case 'create':
      void runConfirm('创建索引', `将在 ES 中创建索引「${row.indexAlias}」。`, () =>
        indexTypeApi.createIndex(row.typeCode),
      )
      return
    case 'rebuild':
      void runConfirm(
        '重建索引',
        `将重建「${row.indexAlias}」并重新灌入该类型的全部已发布内容。` +
          '数据量大时耗时较长（这是同步接口，页面会一直等待），期间检索走旧索引。',
        () => indexTypeApi.rebuildIndex(row.typeCode),
      )
      return
    case 'rebuildSuggest':
      void runConfirm(
        '重建联想词库（含灌词）',
        `将重建「${row.typeCode}」的联想索引并重新灌入建议词（只灌已发布内容）。` +
          '如果你遇到的是「联想索引在、但搜索框下拉没有词」，要执行的是这一个，' +
          '而不是「重建索引」—— 那个修的是主索引，不会给联想补词。',
        () => indexTypeApi.rebuildSuggest(row.typeCode),
      )
      return
    case 'analyzers':
      void runConfirm(
        '重载分析器',
        '将重新加载同义词与停用词。注意它只对之后写入的文档生效，' +
          '存量内容要重建索引才会按新词典切词。',
        () => indexTypeApi.reloadAnalyzers(row.typeCode),
      )
      return
    case 'deleteIndex':
      void runConfirm(
        '删除索引',
        `将从 ES 中删除「${row.indexAlias}」。这是不可逆的：索引里的文档不会回到别处，` +
          '只能靠重建索引从源表重新灌一遍。如果目的是「下架这个类型」，' +
          '请改用编辑配置里的「启用」开关，不要删索引。',
        () => indexTypeApi.deleteIndex(row.typeCode),
      )
      return
    default:
      return
  }
}

/**
 * 二次确认后执行。
 *
 * ⚠️ 提示文案里**不要**放 HTML、也不要开 `dangerouslyUseHTMLString`：
 * 这些文案插值了 `indexAlias` / `typeCode`（来自数据库里的配置）。
 * 开了 HTML 就等于把"配置里能写什么"变成了"页面上能注入什么"。
 */
async function runConfirm(
  label: string,
  detail: string,
  action: () => Promise<void>,
): Promise<void> {
  try {
    await ElMessageBox.confirm(detail, label, {
      type: 'warning',
      confirmButtonText: label,
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await action()
  ElMessage.success(`${label} 已提交`)
}

async function onPreviewMapping(row: IndexTypeConfig): Promise<void> {
  previewType.value = row.typeCode
  drawerVisible.value = true
  drawerLoading.value = true
  mappingRaw.value = ''
  try {
    mappingRaw.value = await indexTypeApi.mappingPreview(row.typeCode)
  } finally {
    drawerLoading.value = false
  }
}

async function onShowStatus(row: IndexTypeConfig): Promise<void> {
  statusDialogVisible.value = true
  status.value = null
  try {
    status.value = await indexTypeApi.indexStatus(row.typeCode)
  } catch {
    status.value = null
  }
}

/* ------------------------------ 删除配置 ------------------------------ */

/**
 * 删除类型配置。
 *
 * 后端会拦下两种情况（索引还在、还有内容），并把原因写在错误信息里 ——
 * 因此这里的确认框只需要把"为什么会有这两条前置条件"讲清楚，
 * 不必自己再判断一遍（前端判不出索引状态，也没有内容计数）。
 */
async function onDeleteConfig(row: IndexTypeConfig): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(
      // 不换行：ElMessageBox 的 message 是纯文本节点，`\n` 不会渲染成换行，
      // 而开 HTML 又会把配置里的字符串变成可注入内容。分段用分号表达即可。
      `将删除类型配置「${row.typeName}（${row.typeCode}）」。` +
        '这不是删数据，但有两条前置条件，不满足会被服务端拒绝：' +
        '一是该类型的 ES 索引必须已经删掉 —— 配置一删，这个类型就被当作不存在，' +
        '索引从此再也删不掉（只能登进 ES 手工清）；' +
        '二是该类型下不能还有内容 —— 否则那些内容会变成「查不到也管不了」的孤儿。' +
        '正确顺序是先删索引、再清空内容，最后才删配置。',
      '删除类型配置',
      {
        type: 'warning',
        confirmButtonText: '删除配置',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  await indexTypeApi.removeIndexType(row.id)
  ElMessage.success('类型配置已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>一个 typeCode = 一份字段配置 = 一个 ES 索引</template>
      类型的编码会被拼进索引名（别名 <code>kuma_&lt;typeCode&gt;</code>，物理索引
      <code>kuma_&lt;typeCode&gt;_vN</code>），同时是检索接口的路径参数、ES 查询的类型过滤条件、
      审核队列的筛选口径。<b>改 typeCode 等于换一份索引</b>，所以创建后不可修改。
      <br />
      配置改完<b>不会自动生效</b>：字段配置决定索引结构，要执行「重建索引」才对存量数据生效。
    </el-alert>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="类型编码 / 名称"
        clearable
        style="width: 240px"
        @keyup.enter="onSearch"
      />
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button type="success" @click="openCreate">新增索引类型</el-button>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="filteredRows" border stripe>
      <el-table-column prop="typeCode" label="类型编码" width="140" />
      <el-table-column prop="typeName" label="类型名称" width="140" />
      <el-table-column prop="indexAlias" label="索引别名" min-width="150" />
      <el-table-column prop="currentVersion" label="版本" width="70" />
      <el-table-column prop="shards" label="分片" width="70" />
      <el-table-column prop="replicas" label="副本" width="70" />
      <el-table-column label="允许投稿" width="100">
        <template #default="{ row }">
          <el-tag :type="row.allowUserSubmit === 1 ? 'warning' : 'info'" size="small">
            {{ row.allowUserSubmit === 1 ? '开放' : '关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑配置</el-button>
          <el-button link type="danger" @click="onDeleteConfig(row)">删除配置</el-button>
          <!--
            索引运维操作收进下拉：它们是"已经配好之后偶发才用到"的一组，
            和「编辑配置 / 删除配置」摊在同一行里会让日常入口淹没在 7 个链接中。
          -->
          <el-dropdown @command="(cmd: string) => onRowCommand(cmd, row)">
            <el-button link type="primary">索引操作 ▾</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="mapping">mapping 预览</el-dropdown-item>
                <el-dropdown-item command="status">索引状态</el-dropdown-item>
                <el-dropdown-item command="create" divided>创建索引</el-dropdown-item>
                <el-dropdown-item command="rebuild">重建索引</el-dropdown-item>
                <el-dropdown-item command="rebuildSuggest">重建联想词库（含灌词）</el-dropdown-item>
                <el-dropdown-item command="analyzers">重载分析器</el-dropdown-item>
                <el-dropdown-item command="deleteIndex" divided>删除索引</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <!-- ------------------------------ 新建 / 编辑配置 ------------------------------ -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEditing ? `编辑类型配置：${form.typeCode}` : '新增索引类型'"
      width="1100px"
      top="5vh"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="类型编码" prop="typeCode">
              <el-input
                v-model="form.typeCode"
                :disabled="isEditing"
                placeholder="如 article"
              />
              <div class="field-hint">
                {{
                  isEditing
                    ? '创建后不可修改：它同时是索引名、接口路径参数与查询过滤条件。要换请新建一个类型。'
                    : '小写字母 / 数字 / 下划线，字母开头。会被拼进 ES 索引名，因此有这些限制。'
                }}
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类型名称" prop="typeName">
              <el-input v-model="form.typeName" placeholder="如 文章" />
              <div class="field-hint">给人看的名字，出现在搜索页的类型下拉里。</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="索引别名">
              <el-input
                v-model="form.indexAlias"
                :disabled="isEditing"
                placeholder="留空则用 kuma_<typeCode>"
              />
              <div class="field-hint">
                检索端只认别名，重建索引时靠切换别名指向的物理索引实现零停机。
                创建后不可改。
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="业务主键字段">
              <el-input v-model="form.pkField" placeholder="id" />
              <div class="field-hint">源表里用作文档 _id 来源的字段名。</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="主分片数">
              <el-input-number
                v-model="form.shards"
                :min="1"
                :max="20"
                :disabled="isEditing"
                style="width: 100%"
              />
              <div class="field-hint">索引建好后<b>不能改</b>，改动只能靠重建。</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="副本数">
              <el-input-number v-model="form.replicas" :min="0" :max="5" style="width: 100%" />
              <div class="field-hint">可以随时改，单节点环境下保持 0。</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="启用">
              <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
              <div class="field-hint">停用后该类型不再参与检索，内容与索引都保留。</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="允许前台投稿">
          <el-switch v-model="form.allowUserSubmit" :active-value="1" :inactive-value="0" />
          <div class="field-hint" :class="{ danger: form.allowUserSubmit === 1 }">
            {{
              form.allowUserSubmit === 1
                ? '已开放：任何持投稿权限的登录用户都能往这个类型写内容（会落「待审」，需在内容维护页放行才可被搜到）。'
                : '关闭中。投稿是一个「对外写入」的入口，默认关闭，必须逐个类型显式打开。'
            }}
          </div>
        </el-form-item>

        <el-form-item label="字段配置">
          <FieldConfigEditor ref="editorRef" />
        </el-form-item>

        <el-form-item label="检索配置">
          <div class="field-hint">
            检索配置（<code>searchConfig</code>：默认检索字段、排序、facet 等）不在本页编辑，
            保留原值不变。需要改动请直接调接口或改库后再重载配置。
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- mapping 预览抽屉：后端返回的是 JSON 字符串 -->
    <el-drawer v-model="drawerVisible" :title="`mapping 预览：${previewType}`" size="50%">
      <div v-loading="drawerLoading">
        <pre class="mapping-json">{{ mappingPretty || '暂无 mapping 数据' }}</pre>
      </div>
    </el-drawer>

    <!-- 索引状态 -->
    <el-dialog v-model="statusDialogVisible" title="索引状态" width="560px">
      <el-descriptions v-if="status" :column="1" border>
        <el-descriptions-item label="类型编码">{{ status.typeCode ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="别名">{{ status.alias ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="当前版本">{{ status.currentVersion ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="主索引">{{ status.physicalIndex ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="主索引是否存在">
          <el-tag :type="status.exists ? 'success' : 'danger'" size="small">
            {{ status.exists ? '存在' : '不存在' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="联想索引">{{ status.suggestIndex ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="联想索引">
          <template v-if="status.suggestRequired">
            <el-tag :type="status.suggestExists ? 'success' : 'danger'" size="small">
              {{ status.suggestExists ? '存在' : '缺失' }}
            </el-tag>
          </template>
          <el-tag v-else type="info" size="small">该类型不需要（没有字段声明 suggest）</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="文档数">{{ status.documentCount ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="源数据行数">{{ status.sourceRowCount ?? '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="status"
        :type="isIndexHealthy(status) ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="status-alert"
      >
        <template #title>
          {{ isIndexHealthy(status) ? '索引齐了' : '索引不完整' }}
        </template>
        <template v-if="status.missingIndexes && status.missingIndexes.length">
          缺失：<code>{{ status.missingIndexes.join('、') }}</code>。
          缺失的若是联想索引，搜索本身仍可用，但每次输入提示都会以一条
          <code>no such index</code> 的告警收场 —— 执行「重建联想词库（含灌词）」即可补齐。
        </template>
        <template v-else-if="isIndexHealthy(status)">
          主索引与联想索引都存在。注意：<b>文档数不等于检索结果数</b>，
          状态不是「已发布」的内容也在索引里，只是查询时被过滤掉。
        </template>
      </el-alert>

      <el-empty v-if="!status" description="暂无状态信息" />
    </el-dialog>

    <div class="table-footer">
      <span>共 {{ filteredRows.length }} 条</span>
    </div>
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

.mapping-json {
  margin-top: 12px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  overflow: auto;
  max-height: 60vh;
}

.field-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}

.field-hint.danger {
  color: #f56c6c;
}

.status-alert {
  margin-top: 12px;
}
</style>
