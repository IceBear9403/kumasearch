<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import * as indexTypeApi from '@/api/admin/indexType'
import type { FieldConfig, FieldType } from '@/types/admin'

/**
 * `field_config` 编辑器：可视化表格 ⇄ JSON 文本，两视图共用同一份数据。
 *
 * <h3>为什么必须有可视化这一侧</h3>
 * `field_config` 是一段 JSON，而它决定的是**索引怎么建**。手写它的人要同时记住
 * 15 个属性名、9 个类型名、以及它们之间哪些组合会被后端拒绝
 * （nested 不能聚合、非 text 不能配 analyzer…）。记不住的后果不是报错，
 * 而是**建出一个错的索引**：能建、能写、查询时才以
 * `Fielddata is disabled on text fields` 这种没人看得懂的形态炸开。
 * 表格把"合法的组合"变成唯一能选出来的东西。
 *
 * <h3>为什么还要留 JSON 那一侧</h3>
 * 总有表格画不下的配置（将来新增的属性、批量粘贴一份现成配置）。
 * 只给表格的话，那些场景就得回去改数据库。
 *
 * <h3>两视图之间的切换规则（这是本组件最容易写错的地方）</h3>
 * - 切到 JSON：把表格数据序列化过去，顺带格式化，好读；
 * - 切回表格：**必须先 parse 成功**。失败就停在 JSON 视图并显示错误位置 ——
 *   若"切过去再说"，用户刚敲的一大段配置会被无声丢掉，而他只会看到"切了一下，改动没了"。
 *
 * <h3>省不掉的最后一道</h3>
 * 保存前调用后端的 `POST /validate-field-config`（只校验不落库）。前端的联动禁用只是
 * 让人少走弯路，**不是**校验 —— 两边规则一旦漂移，以后端为准这件事必须由代码保证，
 * 而不是由"我们记得同步改"保证。
 */

const emit = defineEmits<{ (e: 'change'): void }>()

/** 两视图共用的数据。JSON 视图里的内容只有在 parse 成功后才回写到这里 */
const fields = ref<FieldConfig[]>([])
const viewMode = ref<'visual' | 'json'>('visual')

const jsonText = ref('')
/** JSON 视图的实时校验错误；非空时**不允许**切回表格、也不允许保存 */
const jsonError = ref('')

const FIELD_TYPE_OPTIONS: Array<{ value: FieldType; label: string }> = [
  { value: 'TEXT', label: 'TEXT 文本（分词）' },
  { value: 'KEYWORD', label: 'KEYWORD 关键词（整体匹配）' },
  { value: 'INTEGER', label: 'INTEGER 整数' },
  { value: 'LONG', label: 'LONG 长整数' },
  { value: 'DOUBLE', label: 'DOUBLE 小数' },
  { value: 'BOOLEAN', label: 'BOOLEAN 布尔' },
  { value: 'DATE', label: 'DATE 日期' },
  { value: 'NESTED', label: 'NESTED 嵌套对象' },
  { value: 'GEO_POINT', label: 'GEO_POINT 经纬度' },
]

/* ------------------------------ 联动规则 ------------------------------ */

/*
 * 下面四个判断与后端 `IndexTypeConfigServiceImpl#validateFieldConfig` 一一对应。
 * 它们只负责"把选不出来的选项禁掉"，真正的拦截仍在保存那一步 ——
 * 前端少画一个勾选框，不能替代后端的拒绝。
 */

/** TEXT 才有分词器可配。其余类型配了不会生效，后端直接报错 */
function canAnalyze(row: FieldConfig): boolean {
  return row.type === 'TEXT'
}

/** 拼音子字段只对 TEXT / KEYWORD 有意义 */
function canUseKeywordSubField(row: FieldConfig): boolean {
  return row.type === 'TEXT' || row.type === 'KEYWORD'
}

/** NESTED 没有 `.keyword` 那样的子字段，直接聚合/排序会在查询期炸 */
function canFacetOrSort(row: FieldConfig): boolean {
  return row.type !== 'NESTED'
}

/**
 * 类型变更后，把它管不到的开关清掉。
 *
 * 不清的话会留下一个"表格里看得见、后端必拒"的组合，而用户已经看不出是哪一个勾导致的
 * —— 他只会看到保存时报错，然后回来一个个试。清掉是唯一能保证表格里始终只有合法组合的做法。
 */
function onTypeChange(row: FieldConfig): void {
  if (!canFacetOrSort(row)) {
    row.facetable = false
    row.sortable = false
  }
  if (!canUseKeywordSubField(row)) {
    row.pinyin = false
    row.suggest = false
    delete row.suggestWeight
  }
  if (!canAnalyze(row)) {
    delete row.analyzer
    delete row.searchAnalyzer
  }
  notifyChange()
}

/** 表格里任何一处编辑都让父组件知道值变了（父组件用它决定保存按钮的可用性/脏标记） */
function notifyChange(): void {
  emit('change')
}

/* ------------------------------ 行操作 ------------------------------ */

function addField(): void {
  fields.value.push({ name: '', label: '', type: 'TEXT' })
  notifyChange()
}

function removeField(index: number): void {
  fields.value.splice(index, 1)
  notifyChange()
}

function moveField(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= fields.value.length) return
  const [item] = fields.value.splice(index, 1)
  if (!item) return
  fields.value.splice(target, 0, item)
  notifyChange()
}

/* ------------------------------ 序列化 ------------------------------ */

/**
 * 去掉空串与空数组再序列化。
 *
 * `""` 与 `[]` 在配置里没有意义（后端把空串的 analyzer 当作没配），留着只会让
 * 这份 JSON 变长、让人以为"这里配了什么"。`false` **保留**：它是布尔属性的显式取值，
 * 写出来比省略更好读，而这份配置是给人看的。
 */
function cleanField(field: FieldConfig): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(field)) {
    if (value === undefined || value === null) continue
    if (typeof value === 'string' && value.trim() === '') continue
    if (Array.isArray(value) && value.length === 0) continue
    result[key] = value
  }
  return result
}

function serialize(pretty: boolean): string {
  const list = fields.value.map(cleanField)
  return JSON.stringify(list, null, pretty ? 2 : 0)
}

/* ------------------------------ 视图切换 ------------------------------ */

function switchView(next: 'visual' | 'json'): void {
  if (next === viewMode.value) return

  if (next === 'json') {
    jsonText.value = serialize(true)
    jsonError.value = ''
    viewMode.value = 'json'
    return
  }

  // 切回表格前必须 parse 成功。失败就停在 JSON 视图 —— 见文件头的第三条。
  const parsed = tryParseFieldArray(jsonText.value)
  if (!parsed) {
    return
  }
  fields.value = parsed
  jsonError.value = ''
  viewMode.value = 'visual'
  notifyChange()
}

/** JSON 文本框的实时校验：一边敲一边提示错误位置，但不改数据 */
function onJsonInput(text: string): void {
  jsonError.value = analyzeJson(text)
}

/** 失焦时若已合法就立刻并入数据，免得"改了但没生效" */
function onJsonBlur(): void {
  const parsed = tryParseFieldArray(jsonText.value)
  if (parsed) {
    fields.value = parsed
    jsonError.value = ''
    notifyChange()
  }
}

/** 返回错误文案；空串表示合法 */
function analyzeJson(text: string): string {
  if (!text.trim()) return ''
  try {
    const parsed: unknown = JSON.parse(text)
    if (!Array.isArray(parsed)) return '顶层必须是一个数组：[ { 字段 }, ... ]'
    return ''
  } catch (e) {
    return (e as Error).message
  }
}

function tryParseFieldArray(text: string): FieldConfig[] | null {
  const error = analyzeJson(text)
  if (error) {
    jsonError.value = error
    return null
  }
  if (!text.trim()) {
    return []
  }
  return JSON.parse(text) as FieldConfig[]
}

/* ------------------------------ 对外接口 ------------------------------ */

/** 用一段 field_config JSON 初始化编辑器。解析失败时直接落在 JSON 视图并显示错误 */
function loadFrom(fieldConfigJson?: string): void {
  const text = fieldConfigJson?.trim() ?? ''
  const error = analyzeJson(text)
  if (error) {
    jsonText.value = text
    jsonError.value = error
    viewMode.value = 'json'
    fields.value = []
    return
  }
  fields.value = text ? (JSON.parse(text) as FieldConfig[]) : []
  jsonText.value = ''
  jsonError.value = ''
  viewMode.value = 'visual'
}

/** 取当前配置的 JSON 字符串；JSON 视图有语法错误时返回 null */
function buildJson(): string | null {
  if (viewMode.value === 'json') {
    const parsed = tryParseFieldArray(jsonText.value)
    return parsed ? JSON.stringify(parsed) : null
  }
  return serialize(false)
}

/** 当前生效的字段数组，供前端自查 */
function currentFields(): FieldConfig[] {
  return viewMode.value === 'json' ? (tryParseFieldArray(jsonText.value) ?? []) : fields.value
}

async function validate(): Promise<boolean> {
  const list = currentFields()

  if (list.length === 0) {
    ElMessage.error('至少需要一个字段，否则这个类型没有任何可索引的内容')
    return false
  }

  const seen = new Set<string>()
  for (const field of list) {
    const name = field.name?.trim()
    if (!name) {
      ElMessage.error('存在未命名字段：每个字段的「字段名」都不能为空')
      return false
    }
    if (seen.has(name)) {
      ElMessage.error(`字段名重复：${name}`)
      return false
    }
    seen.add(name)
  }

  const json = buildJson()
  if (json === null) {
    ElMessage.error('field_config 的 JSON 有语法错误，请先修正')
    return false
  }

  // 后端的校验是最后一道，且它的错误信息比前端能给的更准（会指出是哪个字段的哪个属性）。
  // 请求失败时全局拦截器已经把 message 弹出来了，这里只需返回 false。
  try {
    await indexTypeApi.validateFieldConfig(json)
    return true
  } catch {
    return false
  }
}

defineExpose({ loadFrom, buildJson, validate })

const hasFields = computed(() => fields.value.length > 0)

/** 视图切换的入口。el-radio-group 只能给出 `string | number | boolean`，这里收窄 */
function onViewChange(value: string | number | boolean | undefined): void {
  switchView(value === 'json' ? 'json' : 'visual')
}
</script>

<template>
  <div class="field-config-editor">
    <div class="view-switch">
      <el-radio-group :model-value="viewMode" size="small" @change="onViewChange">
        <el-radio-button value="visual">可视化</el-radio-button>
        <el-radio-button value="json">JSON</el-radio-button>
      </el-radio-group>
      <span class="hint">
        两种视图是同一份数据。切到 JSON 会格式化；切回可视化需要 JSON 合法。
      </span>
    </div>

    <!-- ------------------------------ 可视化 ------------------------------ -->
    <template v-if="viewMode === 'visual'">
      <el-button type="primary" size="small" class="add-btn" @click="addField">添加字段</el-button>

      <el-table v-if="hasFields" :data="fields" border size="small">
        <el-table-column type="expand">
          <template #default="{ row, $index }">
            <el-form label-width="120px" class="advanced-form">
              <el-form-item label="索引分词器">
                <el-input
                  v-model="row.analyzer"
                  :disabled="!canAnalyze(row)"
                  placeholder="如 ik_max_word；留空用默认"
                  @change="notifyChange"
                />
                <div class="field-hint">
                  {{
                    canAnalyze(row)
                      ? '仅 TEXT 有意义，决定「写入时怎么切词」。'
                      : '当前类型不分词，该项不生效（已禁用）。'
                  }}
                </div>
              </el-form-item>

              <el-form-item label="查询分词器">
                <el-input
                  v-model="row.searchAnalyzer"
                  :disabled="!canAnalyze(row)"
                  placeholder="如 ik_smart；留空与索引分词器相同"
                  @change="notifyChange"
                />
              </el-form-item>

              <el-form-item label="多值字段">
                <el-switch v-model="row.multiValued" @change="notifyChange" />
                <div class="field-hint">
                  如 tags 这类数组字段。开启后表单里用多选控件，索引侧按数组处理。
                </div>
              </el-form-item>

              <el-form-item label="拼音子字段">
                <el-switch
                  v-model="row.pinyin"
                  :disabled="!canUseKeywordSubField(row)"
                  @change="notifyChange"
                />
                <div class="field-hint">
                  {{
                    canUseKeywordSubField(row)
                      ? '生成全拼 + 首字母两个子字段，用于「拼音也能搜到」。'
                      : '仅 TEXT / KEYWORD 支持。'
                  }}
                </div>
              </el-form-item>

              <el-form-item label="补全建议">
                <el-switch
                  v-model="row.suggest"
                  :disabled="!canUseKeywordSubField(row)"
                  @change="notifyChange"
                />
                <div class="field-hint">
                  {{
                    canUseKeywordSubField(row)
                      ? '写入独立的联想索引，供搜索框下拉提示使用。改这一项要重建联想索引才生效。'
                      : '仅 TEXT / KEYWORD 支持。'
                  }}
                </div>
              </el-form-item>

              <el-form-item label="建议词权重">
                <el-input-number
                  v-model="row.suggestWeight"
                  :min="0"
                  :step="1"
                  :disabled="!canUseKeywordSubField(row)"
                  @change="notifyChange"
                />
              </el-form-item>

              <el-form-item label="日期格式">
                <el-input
                  v-model="row.format"
                  :disabled="row.type !== 'DATE'"
                  placeholder="如 yyyy-MM-dd HH:mm:ss；多种写法用 || 分隔"
                  @change="notifyChange"
                />
                <div class="field-hint danger">
                  {{
                    row.type === 'DATE'
                      ? '⚠️ ES 一旦显式声明格式，就只按这份清单解析、不再回落内置格式。'
                        + '少写一种写法会让整条文档索引失败，而现象只是「保存成功但搜不到」。'
                        + '改这一项必须重建索引才对存量生效。'
                      : '仅 DATE 有意义。'
                  }}
                </div>
              </el-form-item>

              <el-form-item label="自定义过滤器">
                <el-select
                  v-model="row.filters"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  placeholder="一般不用填"
                  style="width: 100%"
                  @change="notifyChange"
                />
              </el-form-item>

              <div class="row-actions">
                <span class="row-index">第 {{ $index + 1 }} 个字段</span>
              </div>
            </el-form>
          </template>
        </el-table-column>

        <el-table-column label="字段名" width="160">
          <template #default="{ row }">
            <el-input v-model="row.name" placeholder="如 title" @change="notifyChange" />
          </template>
        </el-table-column>

        <el-table-column label="展示名" width="140">
          <template #default="{ row }">
            <el-input v-model="row.label" placeholder="如 标题" @change="notifyChange" />
          </template>
        </el-table-column>

        <el-table-column label="类型" width="200">
          <template #default="{ row }">
            <el-select v-model="row.type" @change="onTypeChange(row)">
              <el-option
                v-for="option in FIELD_TYPE_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </template>
        </el-table-column>

        <el-table-column label="可检索" width="80" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.searchable" @change="notifyChange" />
          </template>
        </el-table-column>

        <el-table-column label="可聚合" width="80" align="center">
          <template #default="{ row }">
            <el-checkbox
              v-model="row.facetable"
              :disabled="!canFacetOrSort(row)"
              @change="notifyChange"
            />
          </template>
        </el-table-column>

        <el-table-column label="可排序" width="80" align="center">
          <template #default="{ row }">
            <el-checkbox
              v-model="row.sortable"
              :disabled="!canFacetOrSort(row)"
              @change="notifyChange"
            />
          </template>
        </el-table-column>

        <el-table-column label="高亮" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.highlight" @change="notifyChange" />
          </template>
        </el-table-column>

        <el-table-column label="检索权重" width="130">
          <template #default="{ row }">
            <el-input-number
              v-model="row.boost"
              :min="0"
              :step="0.5"
              :controls="false"
              style="width: 100%"
              @change="notifyChange"
            />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ $index }">
            <el-button link type="primary" :disabled="$index === 0" @click="moveField($index, -1)">
              上移
            </el-button>
            <el-button
              link
              type="primary"
              :disabled="$index === fields.length - 1"
              @click="moveField($index, 1)"
            >
              下移
            </el-button>
            <el-button link type="danger" @click="removeField($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else :image-size="60" description="还没有字段，点上方「添加字段」开始" />

      <div class="field-hint">
        字段名要与内容的实际字段对应：命中 <code>t_search_content</code> 的固定列
        （title / content / tags / categoryId / authorId / price / bizWeight / status / publishTime，
        以后端 <code>ContentFields.ENTITY_COLUMNS</code> 为准）的写进列，其余的写进 <code>extra</code>。
        <b>没有在这里声明过的字段名不会进索引</b> —— 内容表单里填了也搜不到。
      </div>
    </template>

    <!-- ------------------------------ JSON ------------------------------ -->
    <template v-else>
      <el-input
        v-model="jsonText"
        type="textarea"
        :rows="20"
        spellcheck="false"
        placeholder="[ { &quot;name&quot;: &quot;title&quot;, &quot;type&quot;: &quot;TEXT&quot;, ... } ]"
        @input="onJsonInput"
        @blur="onJsonBlur"
      />
      <div v-if="jsonError" class="field-hint danger">JSON 有误：{{ jsonError }}</div>
      <div v-else class="field-hint">
        JSON 合法，失焦后即并入数据。切回「可视化」也会带上这里的内容。
      </div>
    </template>
  </div>
</template>

<style scoped>
.view-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.view-switch .hint {
  font-size: 12px;
  color: #909399;
}

.add-btn {
  margin-bottom: 8px;
}

.advanced-form {
  padding: 12px 20px 0 0;
  background: #fafafa;
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

.row-actions {
  margin-bottom: 12px;
  text-align: right;
}

.row-index {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
