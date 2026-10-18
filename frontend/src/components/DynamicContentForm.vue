<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import type { ContentFieldSchema, ContentTypeSchema, SearchContent } from '@/types/admin'

/**
 * 由字段配置驱动的动态内容表单。**后台内容维护页与前台投稿页共用**。
 *
 * <h3>为什么字段清单是问后端要的，而不是写死</h3>
 * 字段定义在 `t_index_type_config.field_config` 里，是配置。写死的结果是
 * 「后台能配、前台录不了」—— 配置的一半能力被前端吃掉。因此表单的一切
 * （画哪些字段、用什么控件、值落到哪）都从 `GET /admin/content/schema`
 * 或 `GET /content/schema` 的返回推出来。
 *
 * <h3>落点规则：命中固定列就写列，否则写 extra（**由后端说了算**）</h3>
 * `ContentFieldSchema.inExtra` 就是这件事的唯一答案来源。前端**不要**再维护一份
 * 「哪些字段是固定列」的名单 —— 两份名单一旦漂移，症状是「表单里填了值，
 * 保存后却搜不到」，不报任何错，排查方向还会跑到 ES 上去。
 * 对应后端 `ContentFields.ENTITY_COLUMNS` ↔ `DocumentConverter.flatten`。
 *
 * <h3>extra 里那些"不属于任何字段"的键必须原样带回去</h3>
 * `extra` 除了承载配置声明过的字段，还留着 `rejectReason` / `matchedWords`
 * 这类**只存 MySQL、不进索引**的运营痕迹（投稿被驳回的原因就在那里）。
 * 如果表单只管自己认识的那些键、重建整个 extra，一次"打开再保存"就会把驳回原因抹掉。
 * 因此这里把不认识的键收进 `extraUnknown`，提交时原样合并回去。
 *
 * <h3>两种模式</h3>
 * `mode='submit'`（投稿页）会把 `serverManagedFields` 里的字段（status / authorId /
 * bizWeight）整个滤掉：渲染了也会被服务端覆盖，让用户填一个"填了不作数"的框，
 * 比不给他填更糟。`mode='admin'` 则不拦 —— 后台操作者本来就有权设状态、调权重、改作者。
 */

const props = withDefaults(
  defineProps<{
    /** 当前类型的表单结构；为 null 时渲染空态 */
    schema: ContentTypeSchema | null
    /** 使用场景，决定是否滤掉服务端接管的字段、以及 extra 的提示措辞 */
    mode?: 'admin' | 'submit'
    disabled?: boolean
  }>(),
  { mode: 'admin', disabled: false },
)

/** 表单里一个字段的值。多值字段用数组，其余用标量 */
type FieldValue = string | number | boolean | string[] | null

/** 后端没给日期格式时的默认值，与 `FieldConfig.format` 的默认一致 */
const DEFAULT_DATE_VALUE_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/** 内容状态的展示文案。三态来自审核流转，与类型配置的 status（启用/停用）不是一套 */
const STATUS_OPTIONS: Array<{ value: number; label: string }> = [
  { value: 0, label: '0 待审（搜不到）' },
  { value: 1, label: '1 已发布（可被搜到）' },
  { value: 2, label: '2 已驳回（搜不到）' },
]

const formRef = ref<FormInstance>()

/**
 * 字段值。键是字段名，与 el-form-item 的 prop 一一对应。
 *
 * 值类型在这里**只能是 `any`**：同一个 record 里，`title` 是字符串、`price` 是数字、
 * `tags` 是字符串数组、某个开关是布尔 —— 它由运行时的字段配置决定，与模板里的
 * 网格没有一一对应关系。写成 `Record<string, FieldValue>` 会让每个控件的 `v-model`
 * 都需要一次类型断言（而断言不是合法的赋值目标），成本换来的是零保障。
 * 边界仍然收在下面几个转换函数里，它们各自接受 `FieldValue`。
 */
const values = ref<Record<string, any>>({})
/** `extra` 里那些**没有对应字段**的键，原样保留（见文件头第三条） */
const extraUnknown = ref<Record<string, unknown>>({})

/* ------------------------------ extra 原始 JSON ------------------------------ */

const extraRawText = ref('')
const extraError = ref('')
/** 光标是否在 extra 文本框里。在里面时不要用模型回写覆盖他正在敲的文本 */
const extraEditing = ref(false)

/* ------------------------------ 字段推导 ------------------------------ */

const visibleFields = computed<ContentFieldSchema[]>(() => {
  const schema = props.schema
  if (!schema) return []
  const fields = schema.fields ?? []
  if (props.mode !== 'submit') return fields
  const managed = new Set(schema.serverManagedFields ?? [])
  return fields.filter((field) => !managed.has(field.name))
})

const fieldsByName = computed(() => {
  const map = new Map<string, ContentFieldSchema>()
  for (const field of visibleFields.value) map.set(field.name, field)
  return map
})

/** 落进 extra 的字段名 */
const extraFieldNames = computed(
  () => new Set(visibleFields.value.filter((f) => f.inExtra).map((f) => f.name)),
)

/**
 * 校验规则。
 *
 * ⚠️ **只给 title 加必填**，这是后端唯一会因为空值拒绝的字段
 * （`ContentServiceImpl` 里 `title 不能为空`）。表单结构里没有 required 标记，
 * 因此不去"猜"哪些字段该必填 —— 猜出来的额外必填项会拦住后端本来接受的提交，
 * 用户看到的是"前端不让存"，却没有任何一条后端规则对应它。
 */
const rules = computed<FormRules>(() => {
  const result: FormRules = {}
  if (fieldsByName.value.has('title')) {
    result.title = [{ required: true, message: '请输入标题', trigger: 'blur' }]
  }
  return result
})

/* ------------------------------ 值 <-> 表单 转换 ------------------------------ */

/** 把后端给的值转成表单里好编辑的形状 */
function toFormValue(field: ContentFieldSchema, raw: unknown): FieldValue {
  if (raw === null || raw === undefined) {
    return field.multiValued ? [] : null
  }
  if (field.multiValued) {
    if (Array.isArray(raw)) return raw.map((item) => String(item))
    // 固定列上的多值字段（如 tags）库里是逗号分隔的一个字符串，extra 里才是数组
    return String(raw)
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
  }
  if (field.type === 'BOOLEAN') {
    return raw === true || raw === 1 || raw === '1' || raw === 'true'
  }
  if (field.type === 'INTEGER' || field.type === 'LONG' || field.type === 'DOUBLE') {
    const num = Number(raw)
    return Number.isFinite(num) ? num : null
  }
  return String(raw)
}

/** 把表单里的值转成提交给后端的形状 */
function toSubmitValue(field: ContentFieldSchema, value: FieldValue): unknown {
  if (field.multiValued) {
    const list = Array.isArray(value) ? value : []
    // 固定列存逗号串、extra 里存数组 —— 两者的差别由落点决定，不由字段类型决定
    return field.inExtra ? list : list.join(',')
  }
  if (field.type === 'BOOLEAN' && !field.inExtra) {
    // 固定列上的布尔必须落成 0/1：那几个 Java 字段是整型，true/false 会被直接拒绝
    return value === true ? 1 : 0
  }
  return value
}

/**
 * 判空。
 *
 * ⚠️ `false` 与 `0` **都是有值**。把它们当空会让「关闭某个开关」「权重设成 0」
 * 永远存不进去，而且表现为"保存成功了但值又变回去了"。
 */
function isEmptyValue(value: FieldValue): boolean {
  if (value === null || value === undefined) return true
  if (typeof value === 'string') return value.trim() === ''
  if (Array.isArray(value)) return value.length === 0
  return false
}

/* ------------------------------ extra 组装 ------------------------------ */

function buildExtraObject(): Record<string, unknown> {
  // 先铺不认识的键，再让当前字段覆盖同名项 —— 顺序反了会把运营痕迹抹掉
  const extra: Record<string, unknown> = { ...extraUnknown.value }
  for (const field of visibleFields.value) {
    if (!field.inExtra) continue
    const value = values.value[field.name]
    if (isEmptyValue(value)) {
      // 清空一个字段 = 不要这个键，而不是留一个 null（null 会在索引里变成一个空值字段）
      delete extra[field.name]
      continue
    }
    extra[field.name] = toSubmitValue(field, value)
  }
  return extra
}

function syncExtraText(): void {
  extraRawText.value = JSON.stringify(buildExtraObject(), null, 2)
}

// 字段值一变就把 extra 文本框刷新一遍。正在文本框里打字时跳过 ——
// 否则用户每敲一个字符都会被"规范化后的文本"重置光标位置。
watch(
  values,
  () => {
    if (!extraEditing.value) syncExtraText()
  },
  { deep: true },
)

/** 解析并应用文本框里的 extra JSON。语法错误时**不应用**，并留下错误信息拦住提交 */
function applyRawExtra(text: string): void {
  const trimmed = text.trim()
  if (!trimmed) {
    // 清空是合法的：extra 里的字段全部可选
    extraUnknown.value = {}
    for (const field of visibleFields.value) {
      if (field.inExtra) values.value[field.name] = field.multiValued ? [] : null
    }
    extraError.value = ''
    return
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch (e) {
    extraError.value = `extra 不是合法的 JSON：${(e as Error).message}`
    return
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    extraError.value = 'extra 必须是一个 JSON 对象（{ ... }），数组和字面量都不行'
    return
  }

  const obj = parsed as Record<string, unknown>
  const declared = extraFieldNames.value
  const unknown: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(obj)) {
    const field = declared.has(key) ? fieldsByName.value.get(key) : undefined
    if (field) {
      values.value[key] = toFormValue(field, value)
    } else {
      unknown[key] = value
    }
  }
  // 文本框里被删掉的字段要跟着清空，否则它会被 values 里的旧值再写回来
  for (const field of visibleFields.value) {
    if (field.inExtra && !(field.name in obj)) {
      values.value[field.name] = field.multiValued ? [] : null
    }
  }
  extraUnknown.value = unknown
  extraError.value = ''
}

function onExtraBlur(): void {
  applyRawExtra(extraRawText.value)
  // 顺序要紧：必须先把文本框的内容应用进模型，再放开回写 ——
  // 反过来的话，模型仍是被文本覆盖前的旧值，一次回写就把用户刚改的东西冲掉了
  extraEditing.value = false
}

/* ------------------------------ 日期格式 ------------------------------ */

/**
 * 把 field_config 里的 ES 日期格式翻成 Element Plus（dayjs）认的 `value-format`。
 *
 * ⚠️ 这一步不能省。选择器产出的字符串会被 ES 按 field_config 里声明的那份格式解析，
 * 两边对不上就是「保存成功、列表里也有，但搜不到」—— 错误只出现在 bulk 响应里，
 * 与"表单填错了"离得非常远。
 *
 * <h3>为什么要先按 `||` 拆</h3>
 * ES 的 `format` 可以并列多种写法，`data.sql` 里 `publishTime` 就是
 * `yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||yyyy-MM-dd||epoch_millis` 四种，
 * 其中 `epoch_millis` 是**格式名**而不是模式串。整串丢给 `value-format` 只会得到一个
 * 谁都不认识的模板，选出来的日期直接是错的。
 * 取第一个能翻译的模式即可 —— 选择器只产出一种写法，ES 那份清单只要**包含**它就够。
 */
function toDayjsFormat(javaFormat?: string): string {
  const raw = javaFormat?.trim()
  if (!raw) return DEFAULT_DATE_VALUE_FORMAT
  const pattern = raw
    .split('||')
    .map((part) => part.trim())
    .find((part) => part && part !== 'epoch_millis' && part !== 'epoch_second')
  return pattern ? translateJavaDatePattern(pattern) : DEFAULT_DATE_VALUE_FORMAT
}

/**
 * 逐个 token 翻译。只翻常见的那几个，认不出来的原样返回
 * （dayjs 与 Java 对字面量的处理是一致的，剩下的差异只出现在罕见组合上）。
 */
function translateJavaDatePattern(pattern: string): string {
  return (
    pattern
      // yyyy 必须排在 yy 前面，否则 'yyyy' 会先被切成 'YYyy'
      .replace(/yyyy/g, 'YYYY')
      .replace(/yy/g, 'YY')
      .replace(/dd/g, 'DD')
      .replace(/HH/g, 'HH')
      .replace(/mm/g, 'mm')
      .replace(/ss/g, 'ss')
      // Java 用单引号包字面量，dayjs 用方括号（如 ISO 里的 'T'）
      .replace(/'([^']*)'/g, '[$1]')
  )
}

/* ------------------------------ 对外接口 ------------------------------ */

/** 重建表单结构（切换类型时调用）。会清空一切已填内容 */
function reset(): void {
  values.value = {}
  extraUnknown.value = {}
  extraError.value = ''
  extraEditing.value = false
  syncExtraText()
  formRef.value?.clearValidate()
}

/**
 * 用一条已有内容填充表单。传 `null` 表示"新建"。
 *
 * 会把固定列的值与 `extra` 里的值一并读出来 —— 读哪边由 `inExtra` 决定，
 * 与提交时写哪边用的是同一份判断，不走两套逻辑。
 */
function loadFrom(content: SearchContent | null): void {
  const extra = parseExtraObject(content?.extra)
  const declared = extraFieldNames.value
  const next: Record<string, FieldValue> = {}

  for (const field of visibleFields.value) {
    const raw = field.inExtra
      ? extra[field.name]
      : (content as Record<string, unknown> | null | undefined)?.[field.name]
    next[field.name] = toFormValue(field, raw)
  }

  const unknown: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(extra)) {
    if (!declared.has(key)) unknown[key] = value
  }

  values.value = next
  extraUnknown.value = unknown
  extraError.value = ''
  extraEditing.value = false
  syncExtraText()
  formRef.value?.clearValidate()
}

/** 取表单当前值，拼成提交给后端的对象。extra JSON 有错时返回 null（调用方据此中断） */
function buildContent(): SearchContent | null {
  const schema = props.schema
  if (!schema || extraError.value) return null

  const content: Record<string, unknown> = { typeCode: schema.typeCode }
  for (const field of visibleFields.value) {
    if (field.inExtra) continue
    const value = values.value[field.name]
    if (isEmptyValue(value)) continue
    content[field.name] = toSubmitValue(field, value)
  }

  // **永远**带上 extra，哪怕是空的 "{}"。
  // 后端更新走 MyBatis-Plus 的 updateById，而它默认跳过 null 字段 ——
  // 也就是说"不传这个键"并不表示"把它清空"，而是"保持原样"。
  // 若空时不传，用户在表单里删光所有额外字段后保存，库里的旧值会原封不动留着，
  // 现象是"删了又回来了"。传 "{}" 是**此刻唯一能真正清空它**的写法。
  content.extra = JSON.stringify(buildExtraObject())
  return content as unknown as SearchContent
}

/** 校验。extra 的语法错误也算不通过 —— 否则用户改坏的 JSON 会被静默丢弃 */
async function validate(): Promise<boolean> {
  if (extraError.value) {
    ElMessage.error('extra JSON 有语法错误，请先修正后再保存')
    return false
  }
  if (!formRef.value) return true
  try {
    await formRef.value.validate()
    return true
  } catch {
    return false
  }
}

/** 切换类型时旧字段的值一律作废：字段清单都变了，留着只会在提交时被静默丢掉 */
watch(
  () => props.schema?.typeCode,
  () => reset(),
)

defineExpose({ loadFrom, buildContent, validate, reset })

/** extra 可能不是合法 JSON（历史数据、人工改过的行），此时当作空对象并让文本框如实显示原文 */
function parseExtraObject(raw?: string): Record<string, unknown> {
  if (!raw || !raw.trim()) return {}
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
  } catch {
    /* 落到下面按空对象处理 */
  }
  return {}
}

const extraCollapseActive = ref<string[]>([])
</script>

<template>
  <el-empty v-if="!schema" description="请先选择内容类型" />

  <div v-else class="dynamic-form">
    <el-form
      ref="formRef"
      :model="values"
      :rules="rules"
      label-width="110px"
      :disabled="disabled"
    >
      <el-form-item
        v-for="field in visibleFields"
        :key="field.name"
        :label="field.label || field.name"
        :prop="field.name"
      >
        <!-- BOOLEAN -->
        <el-switch
          v-if="field.type === 'BOOLEAN'"
          v-model="values[field.name]"
          :active-value="true"
          :inactive-value="false"
        />

        <!-- status：语义化的枚举，用数字框会让人去填 3 -->
        <el-select
          v-else-if="field.name === 'status' && mode === 'admin'"
          v-model="values[field.name]"
          placeholder="请选择状态"
          class="wide"
        >
          <el-option
            v-for="option in STATUS_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>

        <!-- DATE -->
        <el-date-picker
          v-else-if="field.type === 'DATE'"
          v-model="values[field.name]"
          type="datetime"
          :value-format="toDayjsFormat(field.format)"
          placeholder="选择日期时间"
          class="wide"
        />

        <!-- 数值 -->
        <el-input-number
          v-else-if="
            field.type === 'INTEGER' || field.type === 'LONG' || field.type === 'DOUBLE'
          "
          v-model="values[field.name]"
          :step="field.type === 'DOUBLE' ? 0.01 : 1"
          :controls-position="'right'"
        />

        <!-- 多值 KEYWORD（如 tags） -->
        <el-select
          v-else-if="field.type === 'KEYWORD' && field.multiValued"
          v-model="values[field.name]"
          multiple
          filterable
          allow-create
          default-first-option
          placeholder="输入后回车添加，可多值"
          class="wide"
        />

        <!-- 正文用文本域 -->
        <el-input
          v-else-if="field.name === 'content'"
          v-model="values[field.name]"
          type="textarea"
          :rows="8"
          placeholder="正文内容"
        />

        <!-- NESTED / GEO_POINT 没有合适的表单控件 -->
        <template v-else-if="field.type === 'NESTED' || field.type === 'GEO_POINT'">
          <el-input disabled placeholder="该类型没有表单控件" class="wide" />
          <div class="field-hint">
            {{
              field.type === 'NESTED' ? '嵌套对象' : '经纬度坐标'
            }}字段暂不支持在表单里编辑，请在下方「直接编辑 extra JSON」里填写。
            值会原样保留，不动它就不会丢。
          </div>
        </template>

        <!-- 兜底：后端将来新增的字段类型，至少还能填文本，而不是整块表单报错 -->
        <el-input v-else v-model="values[field.name]" class="wide" />

        <div v-if="field.inExtra" class="field-hint">
          该字段写入 <code>extra</code> JSON（不是 t_search_content 的固定列）。
        </div>
      </el-form-item>
    </el-form>

    <el-collapse v-model="extraCollapseActive" class="extra-block">
      <el-collapse-item name="extra">
        <template #title>
          <span>
            直接编辑 extra JSON
            <el-tag v-if="extraError" type="danger" size="small" class="extra-badge">有语法错误</el-tag>
            <el-tag v-else type="info" size="small" class="extra-badge">
              高级 · 嵌套字段只能在这里改
            </el-tag>
          </span>
        </template>

        <div class="field-hint">
          这里的键分两类，行为**不一样**：
          <ul class="hint-list">
            <li>
              在字段配置里**声明过**的键（如 <code>publishTime</code> / <code>tags</code>）会平铺进
              ES 文档，是可检索的业务字段；
            </li>
            <li>
              没声明过的键（如 <code>rejectReason</code> / <code>matchedWords</code>）只留在数据库里、
              **不会进索引**，因此不能靠它搜到内容。它们会被原样保留，不会被表单抹掉。
            </li>
          </ul>
          <span v-if="mode === 'submit'">
            投稿时服务端只接受**该类型声明过**的键，写别的会被拒绝（`30001`）。
          </span>
          <span v-else>
            后台不受这条限制 —— 但新增的键同样不会进索引。
          </span>
        </div>

        <el-input
          v-model="extraRawText"
          type="textarea"
          :rows="10"
          spellcheck="false"
          placeholder="{}"
          @focus="extraEditing = true"
          @blur="onExtraBlur"
        />
        <div v-if="extraError" class="field-hint danger">{{ extraError }}</div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.dynamic-form {
  padding-right: 8px;
}

.wide {
  width: 100%;
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

.hint-list {
  margin: 4px 0 4px 0;
  padding-left: 18px;
}

.extra-block {
  margin-top: 8px;
}

.extra-badge {
  margin-left: 8px;
}
</style>
