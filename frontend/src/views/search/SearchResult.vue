<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Link } from '@element-plus/icons-vue'

import SearchBox from '@/components/SearchBox.vue'
import SearchFilters from '@/components/SearchFilters.vue'
import Pagination from '@/components/Pagination.vue'
import HighlightText from '@/components/HighlightText.vue'
import FacetPanel from './FacetPanel.vue'
import * as searchApi from '@/api/search'
import { useSearchStore } from '@/stores/search'
import { formatNumber, formatScore, formatTook } from '@/utils/format'
import type { FacetBucket, HitDTO, SearchTypeDTO, WebHit } from '@/types/search'

/**
 * 搜索结果页：**两个独立的检索结果分组**。
 *
 * <h3>为什么是两组、两个请求，而不是一个接口加个开关</h3>
 * 站内内容与网页采集内容在服务端是两个接口（`POST /api/search` 与
 * `POST /api/search/web`），这里就必须是两个请求。这么做换来的是**独立降级**：
 * 网页采集侧超时或报错时，站内结果照常渲染，网页那一块显示「加载失败，点击重试」，
 * 而不是整页空白。两组还各自分页、各自有加载态 —— 一个慢不该拖住另一个。
 *
 * <h3>两组的分页是独立的</h3>
 * 翻站内结果的第 2 页不会动网页那一组，反之亦然。它们没有共同的"第 2 页"这个概念，
 * 硬绑在一起只会让用户点一次翻页、两处数字同时跳，而他只想看其中一处。
 */

const route = useRoute()
const router = useRouter()
const searchStore = useSearchStore()
const { query, result, loading, selectedFacets } = storeToRefs(searchStore)

const keyword = ref('')

/* --------------------------- 站内结果（第一组） --------------------------- */

/**
 * 未选类型时，供用户就地补选类型用的可选项。
 *
 * 只在**确实没带类型进来**时（`!query.typeCode`）才请求 ——
 * 带了类型的正常路径一次都不该多发这个请求。
 *
 * 这个地方值得存在，是因为"未指定检索类型"曾是搜索页最容易踩的坑：
 * 首页的类型下拉以前是空的（后端没有公开的类型列表接口），
 * 用户搜完之后在结果页看到一句"站内内容未检索"，却没有任何地方能补救 ——
 * 只能退回首页、重新打字。既然结果页已经知道缺的是什么，就该在这里给出口。
 */
const typeOptions = ref<SearchTypeDTO[]>([])
const loadingTypes = ref(false)
const pendingTypeCode = ref('')

async function loadTypes(): Promise<void> {
  loadingTypes.value = true
  try {
    typeOptions.value = await searchApi.searchTypes()
    if (!pendingTypeCode.value && typeOptions.value.length > 0) {
      pendingTypeCode.value = typeOptions.value[0].typeCode
    }
  } catch {
    // 拦截器已提示。取不到类型只是"补选"这条路走不通，
    // 网页采集那一组照常展示，不该再弹一次错误。
    typeOptions.value = []
  } finally {
    loadingTypes.value = false
  }
}

/** 就地补选类型：写进 store、同步到地址栏，然后重跑站内那一组 */
function applyTypeCode(): void {
  if (!pendingTypeCode.value) return
  searchStore.setTypeCode(pendingTypeCode.value)
  router.replace({
    path: '/search/result',
    query: { ...route.query, typeCode: pendingTypeCode.value },
  })
  void execute()
}

const hitList = computed<HitDTO[]>(() => result.value?.hits ?? [])
const facetList = computed(() => result.value?.facets ?? [])
const total = computed(() => result.value?.total ?? null)
const cursorMode = computed(() => result.value?.cursorMode ?? false)
const nextCursor = computed(() => result.value?.nextCursor ?? null)
const correctedKeyword = computed(() => result.value?.correctedKeyword ?? '')
const partial = computed(() => result.value?.partial ?? false)

/** facet 的展示名映射 */
const facetLabels = computed<Record<string, string>>(() => {
  const labels: Record<string, string> = {}
  facetList.value.forEach((facet) => {
    labels[facet.field] = facet.label || facet.field
  })
  return labels
})

/** 取一条命中的高亮片段 */
function firstHighlight(hit: HitDTO): string {
  const values = Object.values(hit.highlights ?? {})
  for (const list of values) {
    if (list && list.length > 0) return list[0]
  }
  return ''
}

/** 命中的主标题 */
function hitTitle(hit: HitDTO): string {
  const fields = hit.fields ?? {}
  const candidate = fields.title ?? fields.name ?? fields.subject ?? hit.id
  return candidate === null || candidate === undefined ? hit.id : String(candidate)
}

/** 命中的副标题/描述 */
function hitDescription(hit: HitDTO): string {
  const highlight = firstHighlight(hit)
  if (highlight) return highlight
  const fields = hit.fields ?? {}
  const candidate = fields.description ?? fields.summary ?? hit.summary
  return candidate === null || candidate === undefined ? '' : String(candidate)
}

/* ------------------------- 网页采集结果（第二组） ------------------------- */

const webHits = ref<WebHit[]>([])
const webTotal = ref<number | null>(null)
const webDomainFacets = ref<FacetBucket[]>([])
const webTookMillis = ref(0)
const webPartial = ref(false)
const webLoading = ref(false)
/**
 * 网页那一组自己的失败状态。
 *
 * 单独一个 ref 而不是复用 store 的 `loading`：两组的失败必须能分开表达，
 * 否则站内成功、网页失败时会显示成"两处都在转圈"或"两处都空"，
 * 用户无从判断该重试哪一边。
 */
const webFailed = ref(false)
const webPageNum = ref(1)
const webPageSize = ref(10)
/** 站点筛选（最多 10 个，与后端的上限一致） */
const webDomains = ref<string[]>([])

/** 网页结果的标题：优先用高亮片段，命中的词才会被标出来 */
function webTitle(hit: WebHit): string {
  const marked = hit.highlights?.title?.[0]
  return marked || hit.title || hit.sourceUrl
}

/** 网页结果的摘要：同样优先高亮片段 */
function webSummary(hit: WebHit): string {
  const marked = hit.highlights?.content?.[0] ?? hit.highlights?.summary?.[0]
  return marked || hit.summary || ''
}

/**
 * 加载网页采集结果。
 *
 * 关键词取自 store 而不是本组自己的输入框：两组的搜索词必须是同一个，
 * 否则用户会看到"我搜的是 A，网页那一组却在给 B 的结果"。
 */
async function loadWeb(): Promise<void> {
  const text = query.value.keyword?.trim()
  if (!text) {
    webHits.value = []
    webTotal.value = null
    webDomainFacets.value = []
    return
  }
  webLoading.value = true
  webFailed.value = false
  try {
    const data = await searchApi.webSearch({
      keyword: text,
      ...(webDomains.value.length > 0 ? { domains: webDomains.value } : {}),
      pageNum: webPageNum.value,
      pageSize: webPageSize.value,
    })
    webHits.value = data.hits ?? []
    webTotal.value = data.total ?? null
    webDomainFacets.value = data.domainFacets ?? []
    webTookMillis.value = data.tookMillis ?? 0
    webPartial.value = data.partial ?? false
  } catch {
    // 拦截器已提示过一次，这里只标记状态让区块显示重试入口。
    // 保留上一轮结果不清空：翻页失败时把已有结果抹掉，用户会以为"搜索结果没了"。
    webFailed.value = true
  } finally {
    webLoading.value = false
  }
}

function onWebPageChange(payload: { pageNum: number; pageSize: number }): void {
  webPageNum.value = payload.pageNum
  webPageSize.value = payload.pageSize
  void loadWeb()
}

/** 点击站点标签：只筛这个站；再点一次取消 */
function onToggleDomain(domain: string): void {
  webDomains.value = webDomains.value.includes(domain)
    ? webDomains.value.filter((item) => item !== domain)
    : [...webDomains.value, domain]
  webPageNum.value = 1
  void loadWeb()
}

/** 取消站点筛选 */
function clearDomainFilter(): void {
  webDomains.value = []
  webPageNum.value = 1
  void loadWeb()
}

/* -------------------------------- 交互 -------------------------------- */

async function execute(): Promise<void> {
  // 两组一起发起，但**各自 await 自己的失败**（run/loadWeb 内部都吞掉了异常），
  // 因此这里不需要 Promise.allSettled 也不会因为一侧失败而中断另一侧
  await Promise.all([searchStore.run(), loadWeb()])
}

function onSearch(value: string): void {
  searchStore.setKeyword(value)
  // 换了关键词就回第一页：留在第 5 页看一批全新的结果，用户会以为"没搜到"
  webPageNum.value = 1
  webDomains.value = []
  router.replace({ path: '/search/result', query: { ...route.query, keyword: value } })
  void execute()
}

function onToggleFacet(field: string, key: string): void {
  searchStore.toggleFacet(field, key)
  void execute()
}

function onRemoveFacet(field: string, key: string): void {
  searchStore.removeFacet(field, key)
  void execute()
}

function onClearFacets(): void {
  searchStore.clearFacets()
  void execute()
}

function onPageChange(payload: { pageNum: number; pageSize: number }): void {
  query.value.pageNum = payload.pageNum
  query.value.pageSize = payload.pageSize
  void execute()
}

function onNextCursor(): void {
  if (!nextCursor.value) return
  query.value.useCursor = true
  query.value.cursor = nextCursor.value
  void execute()
}

function onPrevCursor(): void {
  // 游标模式无法精确定位上一页，退回到页码模式的前一页
  query.value.cursor = undefined
  query.value.useCursor = false
  query.value.pageNum = Math.max(1, (query.value.pageNum ?? 1) - 1)
  void execute()
}

onMounted(() => {
  const q = route.query
  if (q.typeCode) searchStore.setTypeCode(String(q.typeCode))
  if (q.keyword) {
    keyword.value = String(q.keyword)
    searchStore.setKeyword(String(q.keyword))
  }
  // 没带类型进来时才去取可选项，用于下面那条提示里的「就地补选」
  if (!searchStore.query.typeCode) void loadTypes()
  void execute()
})
</script>

<template>
  <div class="search-result">
    <!-- 顶部搜索条 -->
    <header class="result-header">
      <SearchBox
        v-model="keyword"
        :type-code="query.typeCode"
        size="default"
        @search="onSearch"
      />
      <div class="result-meta">
        <template v-if="total !== null">
          站内找到 <b>{{ formatNumber(total) }}</b> 条结果
        </template>
        <span v-if="result" class="took">耗时 {{ formatTook(result.tookMillis) }}</span>
        <el-tag v-if="partial" type="warning" size="small">部分结果</el-tag>
        <span v-if="!query.typeCode" class="meta-hint">
          未指定检索类型，站内结果可能不完整
        </span>
      </div>
      <el-alert
        v-if="correctedKeyword"
        class="corrected"
        type="info"
        :closable="false"
        :title="`已按「${correctedKeyword}」为您检索`"
      />
    </header>

    <div class="result-body">
      <!-- 左侧聚合面板：只作用于站内那一组 -->
      <aside class="result-aside">
        <FacetPanel
          :facets="facetList"
          :selected="selectedFacets"
          :loading="loading"
          @toggle="onToggleFacet"
          @clear="onClearFacets"
        />
      </aside>

      <main class="result-main">
        <!-- ==================== 第一组：站内内容 ==================== -->
        <section class="result-group">
          <h2 class="group-title">
            站内内容
            <span v-if="total !== null" class="group-count">{{ formatNumber(total) }} 条</span>
          </h2>

          <!--
            未选检索类型时站内这一组无法检索（站内内容按类型建索引）。
            这里明说，而不是显示"没有找到相关内容" —— 后者的意思是
            "搜过了，没东西"，与"根本没搜"是两回事，用户会据此以为库里没这条数据。
          -->
          <el-alert
            v-if="!query.typeCode"
            type="info"
            :closable="false"
            show-icon
            class="group-hint"
          >
            <template #title>未指定检索类型，站内内容未检索</template>
            <p class="hint-text">站内内容按类型分别建索引，选一个类型才能检索。下面展示的是网页采集结果。</p>
            <div v-if="typeOptions.length > 0" class="hint-action">
              <el-select
                v-model="pendingTypeCode"
                size="small"
                placeholder="选择检索类型"
                :loading="loadingTypes"
                class="hint-select"
              >
                <el-option
                  v-for="item in typeOptions"
                  :key="item.typeCode"
                  :label="item.typeName"
                  :value="item.typeCode"
                />
              </el-select>
              <el-button
                type="primary"
                size="small"
                :disabled="!pendingTypeCode"
                @click="applyTypeCode"
              >
                检索站内内容
              </el-button>
            </div>
          </el-alert>

          <SearchFilters
            :filters="selectedFacets"
            :labels="facetLabels"
            @remove="onRemoveFacet"
            @clear="onClearFacets"
          />

          <div v-loading="loading">
            <!--
              未检索时上面的提示已经说清楚了，这里不再报一次"没找到"。

              `partial` 且 0 条时换一句文案：那说明这一组的结果根本不可信
              （分片超时只回来一部分、或该类型的索引还没建，后端会降级成空结果 + partial）。
              此时说"没有找到相关内容"是**假话** —— 它等于告诉用户库里没有这条数据，
              而事实是我们没查成。两种情况的区别正是 `partial` 要传达的。
            -->
            <el-empty
              v-if="query.typeCode && !loading && hitList.length === 0"
              :description="partial ? '该类型的内容暂时无法检索' : '没有找到相关内容'"
            />

            <ul class="hit-list">
              <li v-for="hit in hitList" :key="hit.id" class="hit-item">
                <h3 class="hit-title">{{ hitTitle(hit) }}</h3>
                <p class="hit-desc">
                  <HighlightText :content="hitDescription(hit)" />
                </p>
                <div class="hit-footer">
                  <!-- score 可能缺失（字段排序 + 未算分），见 types/search.ts -->
                  <span class="hit-score">评分 {{ formatScore(hit.score) }}</span>
                  <span class="hit-id">ID: {{ hit.id }}</span>
                </div>
              </li>
            </ul>
          </div>

          <Pagination
            :total="total"
            :page-num="query.pageNum ?? 1"
            :page-size="query.pageSize ?? 10"
            :cursor-mode="cursorMode"
            :next-cursor="nextCursor"
            :has-prev="(query.pageNum ?? 1) > 1"
            :disabled="loading"
            @change="onPageChange"
            @next="onNextCursor"
            @prev="onPrevCursor"
          />
        </section>

        <!-- ==================== 第二组：网页采集内容 ==================== -->
        <section class="result-group">
          <h2 class="group-title">
            网页采集
            <span v-if="webTotal !== null" class="group-count">
              {{ formatNumber(webTotal) }} 条
            </span>
            <span v-if="webTookMillis > 0" class="group-took">
              {{ formatTook(webTookMillis) }}
            </span>
            <el-tag v-if="webPartial" type="warning" size="small">部分结果</el-tag>
          </h2>

          <p class="group-note">
            内容来自第三方站点，我们只收录标题与摘要，点标题会跳转到原文页面。
          </p>

          <!-- 站点分布：点一下只看这个站 -->
          <div v-if="webDomainFacets.length > 0" class="domain-row">
            <span class="domain-label">来源站点：</span>
            <el-tag
              v-for="bucket in webDomainFacets"
              :key="bucket.key"
              class="domain-tag"
              :type="webDomains.includes(bucket.key) ? 'primary' : 'info'"
              :effect="webDomains.includes(bucket.key) ? 'dark' : 'plain'"
              size="small"
              @click="onToggleDomain(bucket.key)"
            >
              {{ bucket.key }} ({{ bucket.docCount }})
            </el-tag>
            <el-button
              v-if="webDomains.length > 0"
              link
              type="primary"
              size="small"
              @click="clearDomainFilter"
            >
              取消站点筛选
            </el-button>
          </div>

          <el-alert
            v-if="webFailed"
            class="web-failed"
            type="error"
            :closable="false"
            show-icon
          >
            <template #title>网页采集结果加载失败</template>
            <el-button link type="primary" size="small" @click="loadWeb">点击重试</el-button>
          </el-alert>

          <div v-loading="webLoading">
            <!-- 与站内那一组同理：partial 且 0 条时不能说"没有找到" -->
            <el-empty
              v-if="!webLoading && !webFailed && webHits.length === 0"
              :description="webPartial ? '网页采集内容暂时无法检索' : '没有找到网页采集内容'"
              :image-size="60"
            />

            <ul class="hit-list">
              <li v-for="hit in webHits" :key="hit.id" class="hit-item web-hit">
                <!--
                  ⚠️ target="_blank" 与 rel="noopener noreferrer" 都不能省：
                  链接来自第三方页面。少了 noopener，新页面能通过 window.opener
                  改动本页（把用户导去钓鱼站）；少了 noreferrer 会带上 Referer。
                  这里**不是**站内路由跳转，它是一个外链。
                -->
                <h3 class="hit-title">
                  <a
                    class="web-link"
                    :href="hit.sourceUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    <HighlightText :content="webTitle(hit)" />
                    <el-icon class="link-icon"><Link /></el-icon>
                  </a>
                </h3>
                <p class="hit-desc">
                  <HighlightText :content="webSummary(hit)" />
                </p>
                <div class="hit-footer">
                  <span class="hit-domain">{{ hit.domain }}</span>
                  <span class="hit-score">评分 {{ formatScore(hit.score) }}</span>
                </div>
              </li>
            </ul>
          </div>

          <Pagination
            :total="webTotal"
            :page-num="webPageNum"
            :page-size="webPageSize"
            :disabled="webLoading"
            @change="onWebPageChange"
          />
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
.search-result {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px;
}

.result-header {
  margin-bottom: 16px;
}

.result-meta {
  margin-top: 8px;
  color: #606266;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.meta-hint {
  color: #e6a23c;
}

.took {
  color: #909399;
}

.corrected {
  margin-top: 8px;
}

.result-body {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.result-aside {
  width: 220px;
  flex: 0 0 220px;
  background: #fff;
  border-radius: 4px;
  padding: 12px;
}

.result-main {
  flex: 1;
  min-width: 0;
}

.result-group {
  margin-bottom: 28px;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 16px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.group-count {
  font-size: 13px;
  font-weight: 400;
  color: #909399;
}

.group-took {
  font-size: 12px;
  font-weight: 400;
  color: #c0c4cc;
}

.group-note {
  margin: 0 0 10px;
  font-size: 12px;
  color: #909399;
}

.group-hint {
  margin-bottom: 10px;
}

/* 提示里的正文与「就地补选类型」的动作行 */
.hint-text {
  margin: 4px 0 0;
}

.hint-action {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.hint-select {
  width: 180px;
}

.domain-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.domain-label {
  font-size: 12px;
  color: #909399;
}

.domain-tag {
  cursor: pointer;
}

.web-failed {
  margin-bottom: 10px;
}

.hit-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.hit-item {
  background: #fff;
  border-radius: 4px;
  padding: 14px 16px;
  margin-bottom: 12px;
}

.hit-title {
  margin: 0 0 6px;
  font-size: 17px;
  color: #1a1a1a;
}

/* 网页结果的标题是可点的外链，视觉上要与站内标题区分开 */
.web-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #1a1a1a;
  text-decoration: none;
}

.web-link:hover {
  color: #409eff;
  text-decoration: underline;
}

.link-icon {
  font-size: 13px;
  color: #909399;
}

.hit-desc {
  margin: 0 0 8px;
  color: #606266;
  font-size: 14px;
  line-height: 1.6;
}

.hit-footer {
  display: flex;
  gap: 16px;
  color: #909399;
  font-size: 12px;
}

.hit-domain {
  color: #409eff;
}
</style>
