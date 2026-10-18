<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import SearchBox from '@/components/SearchBox.vue'
import * as searchApi from '@/api/search'
import * as localHistory from '@/utils/localHistory'
import type { SearchTypeDTO } from '@/types/search'

/**
 * 搜索主页：大搜索框 + 本机历史 + 热词。
 *
 * <h3>本机历史为什么放在首页最显眼的位置</h3>
 * 「不登录的搜索记录只保存在浏览器中」这条要求，只有让用户**看得见**它才算成立。
 * 之前关键词历史只是一份内存态（刷新即丢），用户没有任何地方能确认
 * "平台到底有没有记住我搜过什么"。把本机历史摆在首页，答案就是：
 * 记了，但记在你自己的浏览器里，服务端不知道。
 *
 * 未选检索类型时展示**全部**历史（跨类型去重）：在用户选定类型之前按类型过滤，
 * 会让列表空着，而他并不知道自己是因为"没选类型"才看不到。
 */
const router = useRouter()

const keyword = ref('')
const typeCode = ref(String(router.currentRoute.value.query.typeCode ?? ''))
const hotWords = ref<string[]>([])
const loadingHot = ref(false)

/**
 * 可选检索类型，来自 `GET /api/search/types`。
 *
 * 这份列表以前是一份**硬编码的空数组**，于是下拉框里一行都没有，
 * 用户实际面对的是一个只能手打 typeCode 的输入框 —— 而 typeCode 是后端的内部概念，
 * 没人知道该填什么，结果就是"什么都不填"，进而站内那一组永远是空的。
 * 类型是**服务端定义**的东西（`t_index_type_config` 里加一行就多一个类型），
 * 前端不可能也不需要猜，只能问后端要。
 */
const typeOptions = ref<SearchTypeDTO[]>([])
const loadingTypes = ref(false)
/** 类型列表请求失败。与"列表为空"是两件事：前者要提示重试，后者是服务端真的没配 */
const typesFailed = ref(false)

/** 本机搜索历史（未选类型时展示全部，见文件头） */
const localKeywords = ref<string[]>([])

/** 是否已经在这个浏览器上留下过搜索痕迹。用来决定要不要显示那一段 */
const hasLocalHistory = computed(() => localKeywords.value.length > 0)

function loadLocal(): void {
  localKeywords.value = typeCode.value
    ? localHistory.keywordsOf(typeCode.value)
    : localHistory.allKeywords()
}

/**
 * 加载可检索类型。
 *
 * <h3>为什么要在拿到列表后替用户选上第一个</h3>
 * 站内内容按类型分别建索引，不选类型就没有站内结果；而"第一个"就是
 * `t_index_type_config` 里最早登记的那个（后端按 id 升序返回，通常是「文章」）。
 * 让用户落地就能直接搜，比让他先理解"类型"这个概念再去选一次要合理 ——
 * 选择器仍然在，想换随时能换，清空它也能只搜网页采集那一组。
 *
 * 这么做的前提是**不能覆盖 URL 里带来的类型**：结果页刷新、或别人分享的链接里带着
 * `?typeCode=xxx` 时，那才是用户的意图，不能被"默认第一个"改掉。
 */
async function loadTypes(): Promise<void> {
  loadingTypes.value = true
  typesFailed.value = false
  try {
    typeOptions.value = await searchApi.searchTypes()
    if (!typeCode.value && typeOptions.value.length > 0) {
      typeCode.value = typeOptions.value[0].typeCode
      // 类型定下来了，本机历史与热词才有的可筛（两者都按类型分组）
      loadLocal()
      void loadHotWords()
    }
  } catch {
    // 拦截器已提示，这里只记状态：类型列表拿不到**不该妨碍搜索本身**
    // （网页采集那一组不需要 typeCode），所以页面照常可用，只多一行说明。
    typesFailed.value = true
    typeOptions.value = []
  } finally {
    loadingTypes.value = false
  }
}

async function loadHotWords(): Promise<void> {
  // 后端 hot 接口的 typeCode 为必填，未选择类型时无需请求
  if (!typeCode.value) {
    hotWords.value = []
    return
  }
  loadingHot.value = true
  try {
    hotWords.value = await searchApi.hotWords({ typeCode: typeCode.value, size: 12 })
  } catch {
    // 拦截器已提示，这里静默
    hotWords.value = []
  } finally {
    loadingHot.value = false
  }
}

function onSearch(value: string): void {
  const text = value.trim()
  if (!text) return
  // 允许不带 typeCode 直接搜：此时结果页只展示网页采集那一组，
  // 站内那一组会提示"未指定检索类型"而不是报错。
  // 让"全网搜索"必须先在某个角落里选一个后端内部概念才能用，说不过去。
  router.push({
    path: '/search/result',
    query: {
      keyword: text,
      ...(typeCode.value ? { typeCode: typeCode.value } : {}),
    },
  })
}

function onHotWord(word: string): void {
  keyword.value = word
  onSearch(word)
}

function onTypeCodeChange(): void {
  loadLocal()
  void loadHotWords()
}

function clearLocal(): void {
  localHistory.clear(typeCode.value || undefined)
  loadLocal()
}

onMounted(() => {
  loadLocal()
  void loadHotWords()
  void loadTypes()
})
</script>

<template>
  <div class="search-home">
    <div class="search-home-card">
      <h1 class="logo">KumaSearch</h1>
      <p class="slogan">高性能全文搜索引擎</p>

      <!--
        类型选择器。选项来自后端（`GET /api/search/types`），**不开放手填** ——
        以前那个 `allow-create` 是被迫的兜底（当时拿不到类型列表），
        它让用户以为 typeCode 是自己随便起的名字，而它是服务端定义好的东西。
      -->
      <div class="type-row">
        <el-select
          v-if="loadingTypes || typeOptions.length > 0 || typeCode"
          v-model="typeCode"
          placeholder="检索类型"
          filterable
          clearable
          :loading="loadingTypes"
          @change="onTypeCodeChange"
        >
          <el-option
            v-for="item in typeOptions"
            :key="item.typeCode"
            :label="item.typeName"
            :value="item.typeCode"
          />
        </el-select>
        <!-- 没有选项时给一句明确的话，而不是一个点开什么都没有的空下拉框 -->
        <span v-else-if="typesFailed" class="type-hint">
          类型列表加载失败，站内内容暂不可选；网页检索仍可使用
        </span>
        <span v-else class="type-hint">暂无可检索的内容类型</span>
      </div>

      <SearchBox
        v-model="keyword"
        :type-code="typeCode"
        placeholder="输入关键词开始搜索"
        @search="onSearch"
      />

      <!--
        本机历史：不登录时它是唯一的历史来源，所以要显式告诉用户
        "它存在哪里"。这也是这一段与热词分开渲染的原因 —— 两者来源完全不同。
      -->
      <div v-if="hasLocalHistory" class="local-history">
        <span class="local-label">本机搜索记录：</span>
        <el-tag
          v-for="word in localKeywords"
          :key="word"
          class="local-tag"
          type="info"
          effect="plain"
          @click="onHotWord(word)"
        >
          {{ word }}
        </el-tag>
        <el-button link size="small" @click="clearLocal">清空</el-button>
        <p class="local-note">这些记录只保存在你的浏览器里，服务端不知道你搜过什么。</p>
      </div>

      <div v-loading="loadingHot" class="hot-words">
        <span class="hot-label">热门搜索：</span>
        <el-tag
          v-for="word in hotWords"
          :key="word"
          class="hot-tag"
          effect="plain"
          @click="onHotWord(word)"
        >
          {{ word }}
        </el-tag>
        <span v-if="!loadingHot && hotWords.length === 0" class="hot-empty">暂无热词</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-home {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 24px;
}

.search-home-card {
  width: 100%;
  max-width: 720px;
  text-align: center;
}

.logo {
  font-size: 44px;
  margin: 0;
  color: #409eff;
  letter-spacing: 2px;
}

.slogan {
  color: #909399;
  margin: 8px 0 28px;
}

.type-row {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}

.type-row :deep(.el-select) {
  min-width: 200px;
}

.type-hint {
  color: #909399;
  font-size: 13px;
}

.local-history {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.local-label {
  color: #909399;
  font-size: 14px;
}

.local-tag {
  cursor: pointer;
}

.local-note {
  flex: 1 0 100%;
  margin: 0;
  font-size: 12px;
  color: #c0c4cc;
}

.hot-words {
  margin-top: 24px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 32px;
}

.hot-label {
  color: #909399;
  font-size: 14px;
}

.hot-tag {
  cursor: pointer;
}

.hot-empty {
  color: #c0c4cc;
  font-size: 13px;
}
</style>
