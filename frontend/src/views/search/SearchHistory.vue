<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as authApi from '@/api/auth'
import * as searchApi from '@/api/search'
import * as localHistory from '@/utils/localHistory'

/**
 * 我的搜索历史。
 *
 * <h3>这个页面同时展示两份历史，因为它们本来就是两份</h3>
 * - **账号历史**：这个账号在任意设备上登录后搜过的词，存在服务端
 *   （`kuma:hist:{userId}:{type}`，Redis ZSET）。
 * - **本机历史**：这台浏览器上搜过的词，**包括没登录时搜的那些**，
 *   存在 localStorage。服务端从头到尾不知道它们。
 *
 * 把它们并排放在一起，用户才第一次有机会看清"我搜过什么"这件事其实分两摊，
 * 并且可以自己决定要不要把匿名的那些并进账号。分成两个页面反而会让人
 * 以为"漏了一部分记录"。
 *
 * <h3>为什么这个页面需要登录</h3>
 * 账号历史是个人的。而**搜索本身不需要登录** —— 未登录的人看不到这个页面，
 * 但他照样能用搜索，他的历史在浏览器里，他在搜索结果页上就能看到。
 * 唯一需要登录的动作是"把它并进账号"，那也正是一个只有账号才谈得上的操作。
 */
const route = useRoute()
const router = useRouter()

const typeCode = ref(String(route.query.typeCode ?? ''))
const serverHistory = ref<string[]>([])
const localKeywords = ref<string[]>([])
const loading = ref(false)
const importing = ref(false)

const hasTypeCode = computed(() => !!typeCode.value)
const canImport = computed(
  () => hasTypeCode.value && localKeywords.value.length > 0 && !importing.value,
)

async function loadServer(): Promise<void> {
  if (!typeCode.value) {
    serverHistory.value = []
    return
  }
  loading.value = true
  try {
    serverHistory.value = await searchApi.searchHistory({ typeCode: typeCode.value, size: 50 })
  } catch {
    // 拦截器已提示。这里保留空列表而不是让页面报错 ——
    // 另一半（本机历史）仍然能看，不该被这一半的失败带下去。
    serverHistory.value = []
  } finally {
    loading.value = false
  }
}

/** 本机历史是同步读的，失败（存储被禁用/内容损坏）在 localHistory 内部兜底为空数组 */
function loadLocal(): void {
  localKeywords.value = typeCode.value ? localHistory.keywordsOf(typeCode.value) : []
}

function reload(): void {
  void loadServer()
  loadLocal()
}

function search(keyword: string): void {
  void router.push({
    path: '/search/result',
    query: { keyword, ...(typeCode.value ? { typeCode: typeCode.value } : {}) },
  })
}

/**
 * 把本机在当前类型下的历史并进账号。
 *
 * 只导当前类型：按钮就在这个类型的列表旁边，用户看到的是这几个词。
 * 而登录成功时的那次询问是另一回事 —— 那次他不知道本机还剩什么，
 * 所以要弹确认；这里是他自己点的，再弹一次只会让人不再读提示。
 */
async function importLocal(): Promise<void> {
  if (!canImport.value) return
  importing.value = true
  try {
    await authApi.importHistory({
      typeCode: typeCode.value,
      keywords: localKeywords.value,
    })
    // 导入成功才清本机这一部分。**只清当前类型** ——
    // 把别的类型也一起清掉，等于用一次点击毁掉用户没打算动的那几组记录。
    localHistory.clear(typeCode.value)
    ElMessage.success('已导入到账号')
    reload()
  } catch {
    // 失败时本机记录原样保留（clear 在 await 之后，没走到），拦截器已提示
  } finally {
    importing.value = false
  }
}

async function clearServer(): Promise<void> {
  if (!hasTypeCode.value) return
  try {
    await ElMessageBox.confirm(
      '将清空本账号在该类型下的全部搜索历史。本机保存的记录不受影响。',
      '清空账号搜索历史',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await searchApi.clearSearchHistory({ typeCode: typeCode.value })
  ElMessage.success('账号搜索历史已清空')
  await loadServer()
}

async function clearLocal(): Promise<void> {
  localHistory.clear(typeCode.value || undefined)
  ElMessage.success('本机搜索历史已清空')
  loadLocal()
}

/** 手动输入或选择类型后重新加载两侧 */
function onTypeCodeChange(): void {
  // 清空类型时把整个 query 置空，而不是塞一个 undefined 进去 ——
  // 后者在地址栏里可能变成 typeCode=undefined 这种把状态写脏的东西
  void router.replace(
    typeCode.value ? { query: { typeCode: typeCode.value } } : { query: {} },
  )
  reload()
}

onMounted(reload)
</script>

<template>
  <div class="history-page">
    <div class="history-header">
      <h2 class="history-title">我的搜索历史</h2>
      <el-select
        v-model="typeCode"
        placeholder="检索类型（typeCode）"
        filterable
        allow-create
        clearable
        style="width: 260px"
        @change="onTypeCodeChange"
      />
    </div>

    <el-alert v-if="!hasTypeCode" type="info" :closable="false" show-icon>
      <template #title>请先选择检索类型</template>
      搜索历史按类型分开存放（搜索结果本身就分类型），因此需要先指定一个 typeCode。
    </el-alert>

    <template v-else>
      <!-- 账号历史：跨设备的那些 -->
      <el-card class="history-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span class="card-title">账号历史</span>
            <span class="card-hint">登录后在本站搜过的词，换设备也在</span>
            <el-button
              link
              type="danger"
              :disabled="serverHistory.length === 0"
              @click="clearServer"
            >
              清空
            </el-button>
          </div>
        </template>
        <div v-loading="loading" class="tag-list">
          <el-tag
            v-for="word in serverHistory"
            :key="word"
            class="history-tag"
            effect="plain"
            @click="search(word)"
          >
            {{ word }}
          </el-tag>
          <span v-if="!loading && serverHistory.length === 0" class="empty">
            暂无账号历史
          </span>
        </div>
      </el-card>

      <!-- 本机历史：包含没登录时搜的那些 -->
      <el-card class="history-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span class="card-title">本机历史</span>
            <span class="card-hint">
              这台浏览器上的记录（含未登录时搜的），只存在本机，服务端看不到
            </span>
            <el-button :disabled="!canImport" :loading="importing" link type="primary" @click="importLocal">
              导入账号
            </el-button>
            <el-button
              link
              type="danger"
              :disabled="localKeywords.length === 0"
              @click="clearLocal"
            >
              清空
            </el-button>
          </div>
        </template>
        <div class="tag-list">
          <el-tag
            v-for="word in localKeywords"
            :key="word"
            class="history-tag"
            type="info"
            effect="plain"
            @click="search(word)"
          >
            {{ word }}
          </el-tag>
          <span v-if="localKeywords.length === 0" class="empty">暂无本机历史</span>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.history-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 16px;
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.history-title {
  margin: 0;
  font-size: 20px;
}

.history-card {
  margin-bottom: 16px;
  border-radius: 4px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.card-title {
  font-weight: 600;
}

.card-hint {
  flex: 1;
  font-size: 12px;
  color: #909399;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 32px;
}

.history-tag {
  cursor: pointer;
}

.empty {
  color: #c0c4cc;
  font-size: 13px;
}
</style>
