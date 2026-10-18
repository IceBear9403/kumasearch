<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import * as crawlApi from '@/api/admin/crawl'
import type { CrawlQueueOverview, CrawlSeed } from '@/types/admin'

/**
 * 采集种子站的运维页。
 *
 * <h3>⚠️ 这个页面上的每个写操作，都在放宽一个「对外发起真实请求」的能力</h3>
 * 采集器会主动去请求第三方站点。因此这一页的交互设计围绕两件事：
 *
 * 1. **加一个站 与 开始采这个站，必须是两个动作。**
 *    后端把新建的站一律落为「停用」，这里也必须让用户看清楚这一点 ——
 *    否则"填完表单点保存"就等于"立刻开始抓对方的网站"，而他以为只是先记一条配置。
 * 2. **配额与速率的默认值是安全线，不是建议值。**
 *    `maxPages` 防的是一次"路径前缀忘填"把百万页站点整个搬进来；
 *    `qps` 防的是把自己送进对方的黑名单。界面上把它们标出来，
 *    而不是埋在表单底部当两个普通输入框。
 *
 * <h3>总开关不在这个页面上</h3>
 * `kuma.crawl.enabled` 默认 false。关着的时候**这里的配置一条也不会执行** ——
 * 后台能看到站、能改配置，但不会有任何请求发出去。这是刻意的：
 * 让"配置好了"与"开始发请求"之间隔着一次部署决策。
 */
const loading = ref(false)
const seeds = ref<CrawlSeed[]>([])

/* ------------------------------ 队列概览 ------------------------------ */

const queueLoading = ref(false)
const queueLoaded = ref(false)
const queueRows = ref<CrawlQueueOverview[]>([])

/* ------------------------------ 表单 ------------------------------ */

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

/** 表单模型。数值字段用 `number | undefined` 而不是字符串，避免提交时来回转换 */
interface SeedForm {
  seedName: string
  entryUrl: string
  domain: string
  allowSubdomain: number
  pathPrefix: string
  maxDepth: number
  maxPages: number
  qps: number
  respectRobots: number
  status: 0 | 1
}

const form = reactive<SeedForm>({
  seedName: '',
  entryUrl: '',
  domain: '',
  // 默认值与后端 CrawlSeedService#create 的默认值保持一致：
  // 前端展示的默认值若与后端不同，用户会以为自己设置生效了，其实没有
  allowSubdomain: 0,
  pathPrefix: '',
  maxDepth: 3,
  maxPages: 5000,
  qps: 0.5,
  respectRobots: 1,
  status: 0,
})

const rules: FormRules = {
  seedName: [{ required: true, message: '请输入种子站名称', trigger: 'blur' }],
  entryUrl: [
    { required: true, message: '请输入入口 URL', trigger: 'blur' },
    {
      pattern: /^https?:\/\/[^\s/]+/i,
      message: '入口 URL 必须是 http 或 https 开头的完整地址',
      trigger: 'blur',
    },
  ],
  domain: [
    { required: true, message: '请输入站点域名', trigger: 'blur' },
    // 域名不带协议、不带路径：带了的话服务端的匹配会永远失败，
    // 症状是"配了站却一页都采不到"，很难往"域名写错了"这个方向想
    {
      pattern: /^[a-zA-Z0-9.-]+$/,
      message: '只填域名本身（如 example.com），不要带 http:// 或路径',
      trigger: 'blur',
    },
  ],
  maxPages: [
    {
      validator: (_rule, value: number, callback) => {
        if (value === null || value === undefined || value <= 0) {
          callback(new Error('页数上限必须大于 0 —— 它是防止误采整个站点的唯一手段'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  qps: [
    {
      validator: (_rule, value: number, callback) => {
        if (value === null || value === undefined || value <= 0) {
          callback(new Error('速率上限必须大于 0'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function load(): Promise<void> {
  loading.value = true
  try {
    seeds.value = await crawlApi.listSeeds()
  } catch {
    seeds.value = []
  } finally {
    loading.value = false
  }
}

async function loadQueue(): Promise<void> {
  queueLoading.value = true
  try {
    queueRows.value = await crawlApi.seedQueueOverview()
    queueLoaded.value = true
  } catch {
    queueRows.value = []
  } finally {
    queueLoading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, {
    seedName: '',
    entryUrl: '',
    domain: '',
    allowSubdomain: 0,
    pathPrefix: '',
    maxDepth: 3,
    maxPages: 5000,
    qps: 0.5,
    respectRobots: 1,
    status: 0,
  })
  dialogVisible.value = true
}

function openEdit(row: CrawlSeed): void {
  editingId.value = row.id ?? null
  Object.assign(form, {
    seedName: row.seedName ?? '',
    entryUrl: row.entryUrl ?? '',
    domain: row.domain ?? '',
    allowSubdomain: row.allowSubdomain ?? 0,
    pathPrefix: row.pathPrefix ?? '',
    maxDepth: row.maxDepth ?? 3,
    maxPages: row.maxPages ?? 5000,
    qps: row.qps ?? 0.5,
    respectRobots: row.respectRobots ?? 1,
    status: (row.status === 1 ? 1 : 0) as 0 | 1,
  })
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (editingId.value === null) {
        await crawlApi.createSeed({ ...form })
        // 提示里必须说到"默认停用"：不说的话用户会以为已经在采了，
        // 然后奇怪为什么待审队列一直是空的
        ElMessage.success('已创建（默认停用）。确认配置无误后，再点「启用」才会开始采集。')
      } else {
        await crawlApi.updateSeed({ id: editingId.value, ...form })
        ElMessage.success('已保存')
      }
      dialogVisible.value = false
      await load()
    } finally {
      saving.value = false
    }
  })
}

async function toggleStatus(row: CrawlSeed): Promise<void> {
  if (row.id === undefined) return
  const next: 0 | 1 = row.status === 1 ? 0 : 1
  if (next === 1) {
    try {
      await ElMessageBox.confirm(
        `启用后采集器将开始请求 ${row.domain}，抓到的页面会进入人工审核队列。` +
          `上限为 ${row.maxPages ?? '未设'} 页、${row.qps ?? '-'} 请求/秒。`,
        '启用采集',
        { type: 'warning', confirmButtonText: '启用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  await crawlApi.changeSeedStatus(row.id, next)
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

/**
 * 删除。
 *
 * 提示语必须说清"队列里尚未抓取的任务会被一并作废" —— 这不是删一条配置，
 * 它会让已经排好队的一批 URL 不再被抓取。笼统地问"确定要删除吗"，
 * 用户不会知道这件事。
 */
async function onDelete(row: CrawlSeed): Promise<void> {
  if (row.id === undefined) return
  try {
    await ElMessageBox.confirm(
      `将删除种子站「${row.seedName}」，并且该站队列中尚未抓取的 URL 会一并作废（不再抓取）。` +
        '已经抓取并入库的内容不受影响。',
      '删除种子站',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await crawlApi.deleteSeed(row.id)
  ElMessage.success('已删除')
  await load()
  if (queueLoaded.value) {
    await loadQueue()
  }
}

/** robots 未遵守时的警示文案 */
function robotsText(value?: number): string {
  return value === 0 ? '不遵守（需有授权依据）' : '遵守'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>采集受总开关控制（<code>kuma.crawl.enabled</code>）</template>
      开关为 false 时，本页的配置一条也不会执行 —— 能看到站点、能改配置，但不会有任何请求发出去。
      新建的种子站一律为「停用」，需要再点一次「启用」才开始采集。
    </el-alert>

    <div class="toolbar">
      <el-button type="success" @click="openCreate">新增种子站</el-button>
      <el-button :loading="queueLoading" @click="loadQueue">查看队列概览</el-button>
      <el-button @click="load">刷新</el-button>
    </div>

    <!-- 队列概览按需加载：它要对每个种子站各跑两次 COUNT，列表页不该无条件承担这份开销 -->
    <div v-if="queueLoaded" class="queue-block">
      <h3 class="queue-title">队列概览</h3>
      <el-table v-loading="queueLoading" :data="queueRows" border size="small">
        <el-table-column prop="seedName" label="种子站" width="180" />
        <el-table-column prop="domain" label="域名" width="180" />
        <el-table-column prop="pendingCount" label="待抓" width="90" />
        <el-table-column prop="fetchingCount" label="抓取中" width="90" />
        <el-table-column prop="doneCount" label="已抓" width="90" />
        <el-table-column prop="failedCount" label="失败待重试" width="110" />
        <el-table-column prop="deadCount" label="死信" width="90" />
        <el-table-column prop="blockedCount" label="robots 拒绝" width="110" />
        <el-table-column label="配额" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.quotaExhausted" type="warning" size="small">已用尽</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!queueLoading && queueRows.length === 0" description="暂无队列数据" />
    </div>

    <el-table v-loading="loading" :data="seeds" border stripe>
      <el-table-column prop="seedName" label="名称" width="160" />
      <el-table-column prop="domain" label="域名" width="180" />
      <el-table-column prop="entryUrl" label="入口 URL" min-width="240" show-overflow-tooltip />
      <el-table-column prop="pathPrefix" label="路径前缀" width="120">
        <template #default="{ row }">{{ row.pathPrefix || '全站' }}</template>
      </el-table-column>
      <el-table-column label="子域名" width="90">
        <template #default="{ row }">
          <el-tag :type="row.allowSubdomain === 1 ? 'warning' : 'info'" size="small">
            {{ row.allowSubdomain === 1 ? '允许' : '不允许' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="maxDepth" label="深度" width="80" />
      <el-table-column prop="maxPages" label="页数上限" width="100" />
      <el-table-column prop="qps" label="QPS" width="80" />
      <el-table-column label="robots" width="150">
        <template #default="{ row }">
          <el-tag :type="row.respectRobots === 0 ? 'danger' : 'success'" size="small">
            {{ robotsText(row.respectRobots) }}
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
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            link
            :type="row.status === 1 ? 'warning' : 'success'"
            @click="toggleStatus(row)"
          >
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增种子站' : '编辑种子站'"
      width="640px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="名称" prop="seedName">
          <el-input v-model="form.seedName" placeholder="仅用于后台展示" />
        </el-form-item>

        <el-form-item label="入口 URL" prop="entryUrl">
          <el-input v-model="form.entryUrl" placeholder="https://example.com/news/" />
        </el-form-item>

        <el-form-item label="域名" prop="domain">
          <el-input v-model="form.domain" placeholder="example.com（不带协议与路径）" />
        </el-form-item>

        <el-form-item label="路径前缀">
          <el-input v-model="form.pathPrefix" placeholder="/news/；留空表示全站" />
          <div class="field-hint">
            强烈建议填写。留空时页数配额会先被首页、关于我们、联系方式这类
            没有检索价值的页面吃掉。
          </div>
        </el-form-item>

        <el-form-item label="允许子域名">
          <el-switch v-model="form.allowSubdomain" :active-value="1" :inactive-value="0" />
          <div class="field-hint">
            放开会把范围从一个站变成一个机构的全部站（www / shop / bbs 是完全不同的内容源）。
            默认不允许。
          </div>
        </el-form-item>

        <el-form-item label="最大深度">
          <el-input-number v-model="form.maxDepth" :min="0" :max="10" />
        </el-form-item>

        <el-form-item label="页数上限" prop="maxPages">
          <el-input-number v-model="form.maxPages" :min="1" :max="1000000" :step="100" />
          <div class="field-hint">
            单种子累计抓取页数的硬上限。路径前缀忘填时，它是唯一能拦住
            「把百万页站点整个搬进来」的东西，不要设得过大。
          </div>
        </el-form-item>

        <el-form-item label="速率上限" prop="qps">
          <el-input-number v-model="form.qps" :min="0.1" :max="10" :step="0.1" :precision="1" />
          <div class="field-hint">
            每秒请求数。0.5 表示 2 秒一个请求 —— 对目标站是可以忽略的负载。
            调高不会让采集快多少，只会更快把自己的 IP 送进对方的黑名单。
          </div>
        </el-form-item>

        <el-form-item label="遵守 robots">
          <el-switch v-model="form.respectRobots" :active-value="1" :inactive-value="0" />
          <div class="field-hint" :class="{ danger: form.respectRobots === 0 }">
            {{
              form.respectRobots === 0
                ? '关闭后请确保已取得站点授权，并把这件事故记在案 —— 这是对外的合规义务。'
                : 'robots.txt 禁止的路径会被直接跳过且不重试。'
            }}
          </div>
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <div class="field-hint">
            新建时建议保持停用，等配置复核无误后再启用。
          </div>
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

.tip {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.queue-block {
  margin-bottom: 20px;
}

.queue-title {
  margin: 0 0 8px;
  font-size: 15px;
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

.muted {
  color: #c0c4cc;
}
</style>
