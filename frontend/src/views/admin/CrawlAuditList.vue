<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as crawlApi from '@/api/admin/crawl'
import type {
  CrawlAuditLog,
  CrawlBatchAction,
  CrawlBatchAuditResult,
  SearchContent,
} from '@/types/admin'

/**
 * 网页采集内容的人工审核队列。
 *
 * <h3>这不是一个可选功能，而是法定义务的落点</h3>
 * 域名已备案、对外公开提供服务，采集来的第三方内容在对外可见之前必须经过人工确认。
 * 这个页面就是"确认"发生的地方，因此它的三个动作在语义上必须分清：
 *
 * | 动作 | 什么时候用 | 后果 |
 * |---|---|---|
 * | 放行 | 待审 → 内容没问题 | 进 ES，开始能被搜到（异步，秒级） |
 * | 驳回 | 待审/已驳回 → 内容有问题 | 不进 ES；若曾发布过则从检索中消失 |
 * | 撤回下架 | 已发布的内容事后发现问题 | 从 ES 中移除，**会走一次删除** |
 *
 * <p>{@code 驳回} 与 {@code 撤回} 刻意没有合并成一个按钮：前者拦在入库之前，
 * 后者作用于**已经对外可见过**的内容。合并会丢掉「这条曾经可见过多久」这个事实，
 * 而投诉与法务场景下恰恰只有它需要被回答（见 `CrawlAuditLog` 的注释）。
 *
 * <h3>批量审核：为什么取消不了"部分成功"这件事</h3>
 * 队列积压到几百条时逐条点是不现实的，所以这个页面同时提供两种批量方式，
 * 它们的**作用范围**不同 —— 这正是它们必须分成两组按钮的原因：
 *
 * | 方式 | 范围 | 适合 |
 * |---|---|---|
 * | 勾选后批量 | 眼前勾中的那几行，跨页不保留 | 逐页审阅，处理当前这一屏 |
 * | 按筛选条件批量 | 库里符合条件的所有待审，一次最多 200 条 | 清积压（例如"某个站整站排除"） |
 *
 * <p>后端返回的是四个计数（`requested` / `succeeded` / `failed` / `skipped`），
 * 而不是一个成功标志，因为这个接口**一定会部分成功**。因此这里的呈现规则是：
 * 有失败时必须停下来列明细（"处理过了"如果包含失败那就是不实的话），
 * 而**跳过不是失败**（状态已经是目标状态或内容不属于采集范围，重试没有意义）——
 * 两者混成一句"有 N 条没成功"，会把人引去重试一批本来就没问题的数据。
 *
 * <p>批量里**没有撤回**：撤回是不可逆的物理移除，一次误操作的代价与"少点几下鼠标"
 * 完全不成比例。后端也只认 `APPROVE` / `REJECT`。
 *
 * <h3>列表里的数据从哪来</h3>
 * 采集内容不是独立的内容体系 —— 它就是 `type_code='webpage'` 的一行
 * `t_search_content`，所以这里拿到的就是 `SearchContent`。
 * `sourceUrl`、`domain`、`matchedWords` 这些网页特有的信息在 `extra` JSON 里，
 * 需要 parse 一次（`extra` 是字符串，不是对象）。
 */
const loading = ref(false)
const rows = ref<SearchContent[]>([])
const pendingCount = ref(0)

/** 筛选条件。status 不传表示「待审 + 已驳回」都要 */
const filters = reactive<{ status: 0 | 2 | undefined; domain: string; matchedWords: string }>({
  status: undefined,
  domain: '',
  matchedWords: '',
})

const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

/** 网页采集内容的 `extra` JSON 结构（键名与后端 `CrawlPipeline#buildExtra` 一致） */
interface WebpageExtra {
  domain?: string
  sourceUrl?: string
  contentHash?: string
  /** 命中的敏感词，逗号分隔。为空表示不是被词库拦下的 */
  matchedWords?: string
  etag?: string
  canonicalUrl?: string
  depth?: number
}

/**
 * 解析 extra。
 *
 * 失败一律返回空对象而不是抛异常：extra 是后端拼出来的字符串，
 * 但它是**采集来的**内容的一部分，格式异常时该让这一行显示得空一些，
 * 而不是让整个审核列表崩掉 —— 那样运维就看不到任何待审内容了。
 */
function parseExtra(row: SearchContent): WebpageExtra {
  try {
    const parsed: unknown = JSON.parse(row.extra ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as WebpageExtra) : {}
  } catch {
    return {}
  }
}

function matchedWordsOf(row: SearchContent): string[] {
  const words = parseExtra(row).matchedWords
  return words ? words.split(',').filter((item) => item.trim() !== '') : []
}

/** 状态标签 */
function statusText(status?: number): string {
  switch (status) {
    case 0:
      return '待审'
    case 1:
      return '已发布'
    case 2:
      return '已驳回'
    default:
      return '未知'
  }
}

function statusTagType(status?: number): 'success' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case 0:
      return 'warning'
    case 1:
      return 'success'
    case 2:
      return 'danger'
    default:
      return 'info'
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const data = await crawlApi.listAuditContents({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      status: filters.status,
      domain: filters.domain || undefined,
      matchedWords: filters.matchedWords || undefined,
    })
    rows.value = data.records ?? []
    total.value = data.total ?? 0
    // 翻页/筛选后旧行的勾选已经没有意义（它们可能已经不在当前列表里），
    // 留着会让"已选 N 条"与眼前看到的对不上 —— 而批量操作最不能容忍的就是这个。
    clearSelection()
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadPendingCount(): Promise<void> {
  try {
    pendingCount.value = await crawlApi.countPendingAudit()
  } catch {
    // 角标拿不到就不显示，不该因此打断整个页面
    pendingCount.value = 0
  }
}

function onSearch(): void {
  pageNum.value = 1
  void load()
}

function onReset(): void {
  filters.status = undefined
  filters.domain = ''
  filters.matchedWords = ''
  onSearch()
}

function onPageChange(page: number): void {
  pageNum.value = page
  void load()
}

function onSizeChange(size: number): void {
  pageSize.value = size
  pageNum.value = 1
  void load()
}

/**
 * 问理由。
 *
 * 理由**允许为空**（后端也是可选的），因为"批量放行一批明显正常的页面"时
 * 逼着人给每条编一句理由，只会让他写'ok'、'ok'、'ok' —— 那比留空更没信息量。
 * 但输入框仍然给出来：真的需要解释时（驳回、撤回）不该没地方写。
 */
async function askReason(title: string, message: string): Promise<string | null> {
  try {
    const result = await ElMessageBox.prompt(message, title, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '可不填。填了会记入审核日志，出问题时能回答"当时为什么这么判"',
      inputValidator: () => true,
      // 理由最长 500，前端先挡一道，免得提交后才被后端拒绝
      inputPattern: /^[\s\S]{0,500}$/,
      inputErrorMessage: '理由不能超过 500 字',
    })
    return result.value ?? ''
  } catch {
    // 用户取消
    return null
  }
}

async function onApprove(row: SearchContent): Promise<void> {
  if (row.id === undefined) return
  const reason = await askReason('放行', `确认放行「${row.title || row.bizId}」？`)
  if (reason === null) return
  await crawlApi.approveContent(row.id, reason)
  ElMessage.success('已放行。内容进入检索需要几秒钟，稍后可在搜索结果中确认。')
  await Promise.all([load(), loadPendingCount()])
}

async function onReject(row: SearchContent): Promise<void> {
  if (row.id === undefined) return
  const reason = await askReason(
    '驳回',
    `确认驳回「${row.title || row.bizId}」？该内容不会进入检索。`,
  )
  if (reason === null) return
  await crawlApi.rejectContent(row.id, reason)
  ElMessage.success('已驳回')
  await Promise.all([load(), loadPendingCount()])
}

/**
 * 撤回下架。
 *
 * 提示语必须说清**后果**，而不是问一句"确定要删除吗"：
 * 这个动作作用于已经对外可见过的内容，点完之后它就从搜索结果里消失了，
 * 而"消失"这件事本身可能是别人正在依赖的（例如有人引用过这个页面）。
 */
async function onTakedown(row: SearchContent): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(
      `「${row.title || row.bizId}」将从搜索结果中移除。` +
        '如果它已被搜索引擎或第三方引用，那些引用不会同步消失。',
      '撤回下架（作用于已发布内容）',
      { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const reason = await askReason('撤回理由', '建议填写：投诉来源、违规类型或发现方式')
  if (reason === null) return
  await crawlApi.takedownContent(row.id, reason)
  ElMessage.success('已撤回')
  await Promise.all([load(), loadPendingCount()])
}

/* ------------------------------ 批量审核 ------------------------------ */

/**
 * 勾选的行（`el-table` 的 selection）。
 *
 * 只存 id 而不是整行：批量请求要的就是 id，而整行会被页面刷新后的新对象替换掉，
 * 留着旧对象只会让"选中的到底是什么"变得含糊。
 */
const selectedIds = ref<number[]>([])
const tableRef = ref<{ clearSelection: () => void }>()

/** 一次请求的条数上限。与后端 `BATCH_LIMIT_DEFAULT` 一致；上限 2000 由后端校验。 */
const BATCH_LIMIT = 200

function onSelectionChange(rows: SearchContent[]): void {
  selectedIds.value = rows.map((row) => row.id).filter((id): id is number => id !== undefined)
}

function clearSelection(): void {
  tableRef.value?.clearSelection()
  selectedIds.value = []
}

/**
 * 展示批量结果。
 *
 * 这里刻意**不是**一句 `ElMessage.success('操作完成')`：
 * 批量操作一定会部分成功，而"哪几条没成、为什么"恰恰是这一步唯一有价值的信息。
 * 四条信息按重要程度依次给出：
 * ① 成功了多少（默认假设，不铺开）；② **失败**（要重试/排查，逐条给原因）；
 * ③ 跳过（不是问题，但要解释清楚"为什么没动它"，否则会被当成失败）；
 * ④ 还剩多少待审（决定"要不要再来一次"）。
 */
async function showBatchResult(
  actionLabel: string,
  result: CrawlBatchAuditResult,
): Promise<void> {
  const succeeded = result.succeeded ?? 0
  const failed = result.failed ?? 0
  const skipped = result.skipped ?? 0
  const remaining = result.remainingPending ?? 0

  let tail = ''
  if (remaining > 0) {
    tail = `\n系统中还有 ${remaining} 条待审，可以再批一次。`
  }

  // 没有失败：一句提示就够，不要用弹窗打断流水线式的清理操作
  if (failed === 0) {
    const skipNote = skipped > 0 ? `，${skipped} 条无需处理` : ''
    ElMessage.success(
      `已${actionLabel} ${succeeded} 条${skipNote}。进检索需要几秒钟。${tail.replace('\n', ' ')}`,
    )
    return
  }

  // 有失败：必须停下来让人看到原因，否则"处理过了"就是一句不实的话
  const details = (result.failures ?? [])
    .map((item) => `· #${item.contentId ?? '?'}：${item.reason ?? '未知原因'}`)
    .join('\n')
  const more =
    failed > (result.failures?.length ?? 0)
      ? `\n（失败共 ${failed} 条，这里只列出前 ${result.failures?.length ?? 0} 条）`
      : ''
  try {
    await ElMessageBox.alert(
      `${actionLabel}成功 ${succeeded} 条，失败 ${failed} 条` +
        `${skipped > 0 ? `，跳过 ${skipped} 条（无需处理，不是错误）` : ''}。\n\n` +
        `失败明细：\n${details}${more}\n\n` +
        '失败的那些没有被改动，仍在待审里；已经成功的不会因为这次失败而回滚，直接重试即可。' +
        tail,
      `批量${actionLabel}结果`,
      { confirmButtonText: '知道了', type: 'warning', customStyle: { whiteSpace: 'pre-line' } },
    )
  } catch {
    // 用户关掉了弹窗，结果不因此丢失 —— 列表和待审角标都已经刷新过
  }
}

/**
 * 执行一次批量操作。
 *
 * `contentIds` 为 null 表示"按当前筛选条件处理"，这是两种危险等级完全不同的操作：
 * 勾选的作用范围是**眼前看得到的这些行**，按条件的范围是**库里的全部符合项**
 * （一次最多 `limit` 条，但可以反复批）。因此这里对后者用的是更强的确认措辞 ——
 * 它会点名"不是这一页，而是所有符合条件的"，而不是笼统的"确认执行吗"。
 */
async function runBatch(action: CrawlBatchAction, contentIds: number[] | null): Promise<void> {
  const actionLabel = action === 'APPROVE' ? '放行' : '驳回'
  const byFilter = contentIds === null

  try {
    await ElMessageBox.confirm(
      byFilter
        ? `将${actionLabel}「当前筛选条件下」的全部待审内容（一次最多 ${BATCH_LIMIT} 条，` +
            '不是只有这一页）。若还需要处理更多，返回结果里会告诉你还剩多少条。'
        : `将${actionLabel}勾选的 ${contentIds.length} 条内容。`,
      byFilter ? `按条件批量${actionLabel}` : `批量${actionLabel}`,
      {
        confirmButtonText: `确认${actionLabel}`,
        cancelButtonText: '取消',
        type: action === 'APPROVE' ? 'info' : 'warning',
        customStyle: { whiteSpace: 'pre-line' },
      },
    )
  } catch {
    return
  }

  const reason = await askReason(
    `批量${actionLabel}`,
    byFilter
      ? `将${actionLabel}符合当前筛选条件的待审内容`
      : `将${actionLabel}勾选的 ${contentIds.length} 条内容`,
  )
  if (reason === null) return

  const result = await crawlApi.batchAudit({
    action,
    contentIds: contentIds ?? undefined,
    // 按 id 时不带条件：虽然后端会以 id 为准，但让请求体如实反映意图更不容易误解
    domain: byFilter ? filters.domain || undefined : undefined,
    matchedWords: byFilter ? filters.matchedWords || undefined : undefined,
    limit: byFilter ? BATCH_LIMIT : undefined,
    reason,
  })

  clearSelection()
  await Promise.all([load(), loadPendingCount()])
  await showBatchResult(actionLabel, result)
}

function onBatchApprove(): void {
  if (selectedIds.value.length === 0) return
  void runBatch('APPROVE', [...selectedIds.value])
}

function onBatchReject(): void {
  if (selectedIds.value.length === 0) return
  void runBatch('REJECT', [...selectedIds.value])
}

/** 按筛选条件批量处理。与勾选无关，因此不受"有没有选中"限制。 */
function onBatchByFilter(action: CrawlBatchAction): void {
  void runBatch(action, null)
}

/* ------------------------------ 审核日志 ------------------------------ */

const logVisible = ref(false)
const logLoading = ref(false)
const logs = ref<CrawlAuditLog[]>([])

function actionText(action?: string): string {
  switch (action) {
    case 'APPROVE':
      return '放行'
    case 'REJECT':
      return '驳回'
    case 'TAKEDOWN':
      return '撤回'
    default:
      return action ?? '-'
  }
}

/**
 * 看某条内容的审核历史。
 *
 * 按 urlHash 查而不是 contentId：内容被撤回后 contentId 会查不到东西，
 * 而 urlHash 永远可重算 —— 这也正是后端同时冗余存这两个标识的原因。
 */
async function openLogs(row: SearchContent): Promise<void> {
  logVisible.value = true
  logLoading.value = true
  logs.value = []
  try {
    // bizId 就是 urlHash（见后端 CrawlPipeline#persist）
    const data = await crawlApi.listAuditLogs({ pageNum: 1, pageSize: 50, urlHash: row.bizId })
    logs.value = data.records ?? []
  } finally {
    logLoading.value = false
  }
}

onMounted(() => {
  void load()
  void loadPendingCount()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-select
        v-model="filters.status"
        placeholder="状态（默认待审 + 已驳回）"
        clearable
        style="width: 200px"
        @change="onSearch"
      >
        <el-option label="待审" :value="0" />
        <el-option label="已驳回" :value="2" />
      </el-select>
      <el-input
        v-model="filters.domain"
        placeholder="来源域名，如 example.com"
        clearable
        style="width: 220px"
        @keyup.enter="onSearch"
      />
      <el-input
        v-model="filters.matchedWords"
        placeholder="命中的敏感词"
        clearable
        style="width: 180px"
        @keyup.enter="onSearch"
      />
      <el-button type="primary" @click="onSearch">查询</el-button>
      <el-button @click="onReset">重置</el-button>

      <div class="pending-badge">
        待审
        <el-badge :value="pendingCount" :hidden="pendingCount === 0" type="warning" />
      </div>
    </div>

    <!--
      批量操作区。分两组，因为它们的**作用范围**不同：
      左边只处理勾中的行（看得见的选择），右边处理当前筛选条件下的全部待审
      （看不见的选择，一次最多 200 条）。混成一组按钮会让"我到底批了什么"变成疑问。
    -->
    <div class="batch-bar">
      <span class="batch-count">
        已选 <b>{{ selectedIds.length }}</b> 条
      </span>
      <el-button type="success" size="small" :disabled="selectedIds.length === 0" @click="onBatchApprove">
        批量放行
      </el-button>
      <el-button type="warning" size="small" :disabled="selectedIds.length === 0" @click="onBatchReject">
        批量驳回
      </el-button>
      <el-button link size="small" :disabled="selectedIds.length === 0" @click="clearSelection">
        清空选择
      </el-button>

      <span class="batch-sep">|</span>
      <span class="batch-hint">按当前筛选条件（不只是这一页）：</span>
      <el-button size="small" @click="onBatchByFilter('APPROVE')">全部放行</el-button>
      <el-button size="small" @click="onBatchByFilter('REJECT')">全部驳回</el-button>

      <span class="batch-note">
        一次最多 200 条，处理完会告诉你还剩多少待审。
      </span>
    </div>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      border
      stripe
      row-key="id"
      @selection-change="onSelectionChange"
    >
      <!--
        勾选列。所有行都可勾：状态判断留给后端（已发布/已被别人处理过的会以 skipped 返回并解释），
        前端"禁用一部分复选框"只会让人以为是自己看错了。
      -->
      <el-table-column type="selection" width="46" />
      <el-table-column label="标题" min-width="240">
        <template #default="{ row }">
          <a
            v-if="parseExtra(row).sourceUrl"
            class="title-link"
            :href="parseExtra(row).sourceUrl"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ row.title || '(无标题)' }}
          </a>
          <span v-else>{{ row.title || '(无标题)' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="来源域名" width="180">
        <template #default="{ row }">{{ parseExtra(row).domain || '-' }}</template>
      </el-table-column>
      <el-table-column prop="content" label="摘要" min-width="240" show-overflow-tooltip />
      <el-table-column label="命中敏感词" width="180">
        <template #default="{ row }">
          <el-tag
            v-for="word in matchedWordsOf(row)"
            :key="word"
            type="danger"
            size="small"
            effect="plain"
            class="word-tag"
          >
            {{ word }}
          </el-tag>
          <span v-if="matchedWordsOf(row).length === 0" class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="种子站" width="140">
        <template #default="{ row }">{{ row.tags || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="success" @click="onApprove(row)">放行</el-button>
          <el-button link type="warning" @click="onReject(row)">驳回</el-button>
          <el-button link type="danger" @click="onTakedown(row)">撤回</el-button>
          <el-button link @click="openLogs(row)">日志</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page="pageNum"
      :page-size="pageSize"
      :page-sizes="[20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="logVisible" title="审核日志" width="760px">
      <el-table v-loading="logLoading" :data="logs" border size="small">
        <el-table-column label="动作" width="80">
          <template #default="{ row }">{{ actionText(row.action) }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作者" width="120" />
        <el-table-column prop="reason" label="理由" min-width="180" show-overflow-tooltip />
        <el-table-column prop="matchedWords" label="命中词" width="160" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
      <el-empty v-if="!logLoading && logs.length === 0" description="暂无审核记录" />
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
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.pending-badge {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: #606266;
  font-size: 13px;
}

.batch-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}

.batch-count b {
  color: #409eff;
  padding: 0 2px;
}

.batch-sep {
  color: #dcdfe6;
  margin: 0 4px;
}

.batch-hint {
  color: #909399;
}

.batch-note {
  margin-left: auto;
  color: #c0c4cc;
  font-size: 12px;
}

.title-link {
  color: #409eff;
  text-decoration: none;
}

.title-link:hover {
  text-decoration: underline;
}

.word-tag {
  margin: 2px 4px 2px 0;
}

.muted {
  color: #c0c4cc;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
