<!--
  脚本调试抽屉（编辑页「一键调试」结果面板）：
  - open(formDef) 由父组件调用：携带编辑器当前内容请求 /api/scripts/debug，
    server 按分组顺序逐接口真实请求一次（串行链路提取变量向后传递）
  - 顶部摘要：接口总数 / 全部通过 / 请求失败 / 断言失败
  - 每接口一个折叠卡片：请求（方法/URL/请求头/请求体）+ 响应（状态码/耗时/响应头/响应体）
    + 断言结果（通过/失败）+ 提取结果（命中值/默认值），失败接口自动展开
  - 配色引用设计令牌，方法徽标与编辑页 m-chip 同风格
-->
<template>
  <el-drawer
    v-model="visible"
    :title="title"
    size="62%"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div v-loading="loading" class="debug-body">
      <!-- 摘要条 -->
      <div v-if="items.length" class="summary-bar">
        <span class="summary-item">共 {{ items.length }} 个接口</span>
        <span class="summary-item ok">通过 {{ okCount }}</span>
        <span v-if="assertFailCount" class="summary-item warn">断言失败 {{ assertFailCount }}</span>
        <span v-if="errorCount" class="summary-item err">请求失败 {{ errorCount }}</span>
        <span class="summary-tip">按分组顺序单线程执行，串行链路提取变量向后传递</span>
      </div>

      <!-- 接口结果卡片 -->
      <div
        v-for="(item, index) in items"
        :key="index"
        class="debug-item"
        :class="{ open: opened === index }"
      >
        <div class="item-head" @click="opened = opened === index ? -1 : index">
          <el-icon class="item-caret" :class="{ open: opened === index }"><ArrowRight /></el-icon>
          <span class="m-chip" :class="methodClass(item.method)">{{ item.method }}</span>
          <span class="item-name">{{ item.name || '未命名接口' }}</span>
          <el-tag v-if="item.error" type="danger" size="small" effect="plain">请求失败</el-tag>
          <el-tag v-else-if="!item.success" type="danger" size="small" effect="plain">
            HTTP {{ item.statusCode }}
          </el-tag>
          <el-tag v-else-if="!item.ok" type="warning" size="small" effect="plain">断言失败</el-tag>
          <el-tag v-else type="success" size="small" effect="plain">通过</el-tag>
          <span v-if="item.elapsedMs != null" class="item-elapsed mono">{{ item.elapsedMs }} ms</span>
        </div>

        <el-collapse-transition>
          <div v-show="opened === index" class="item-detail">
            <!-- 请求 -->
            <div class="detail-title">请求</div>
            <div class="kv-row">
              <span class="kv-label">URL</span>
              <span class="kv-value mono">{{ item.url || '-' }}</span>
            </div>
            <div v-if="item.requestHeaders?.length" class="detail-block">
              <div class="block-label">请求头</div>
              <div v-for="(header, hi) in item.requestHeaders" :key="hi" class="kv-row">
                <span class="kv-label mono">{{ header.k }}</span>
                <span class="kv-value mono">{{ header.v }}</span>
              </div>
            </div>
            <div v-if="item.requestBody" class="detail-block">
              <div class="block-label">请求体</div>
              <pre class="code-block mono">{{ item.requestBody }}</pre>
            </div>

            <!-- 异常信息 -->
            <div v-if="item.error" class="detail-block">
              <div class="detail-title err-title">异常</div>
              <pre class="code-block mono error-text">{{ item.error }}</pre>
            </div>

            <!-- 响应 -->
            <template v-if="item.statusCode != null">
              <div class="detail-title">响应</div>
              <div class="kv-row">
                <span class="kv-label">状态码</span>
                <span class="kv-value">
                  <el-tag :type="item.success ? 'success' : 'danger'" size="small" effect="plain">
                    {{ item.statusCode }}
                  </el-tag>
                </span>
              </div>
              <div class="kv-row">
                <span class="kv-label">耗时</span>
                <span class="kv-value mono">{{ item.elapsedMs }} ms</span>
              </div>
              <div v-if="item.responseHeaders?.length" class="detail-block">
                <div class="block-label">响应头</div>
                <div v-for="(header, hi) in item.responseHeaders" :key="hi" class="kv-row">
                  <span class="kv-label mono">{{ header.k }}</span>
                  <span class="kv-value mono">{{ header.v }}</span>
                </div>
              </div>
              <div class="detail-block">
                <div class="block-label">响应体</div>
                <pre class="code-block mono">{{ item.responseBody || '（空）' }}</pre>
              </div>
            </template>

            <!-- 断言结果 -->
            <template v-if="item.assertions?.length">
              <div class="detail-title">断言（{{ item.assertions.length }}）</div>
              <div v-for="(assertion, ai) in item.assertions" :key="ai" class="kv-row">
                <span class="kv-label">{{ assertion.type === 'CODE' ? '响应码相等' : '文本包含' }}</span>
                <span class="kv-value">
                  <el-tag :type="assertion.passed ? 'success' : 'danger'" size="small" effect="plain">
                    {{ assertion.passed ? '通过' : '失败' }}
                  </el-tag>
                  <span class="expect-text mono">{{ assertion.expect }}</span>
                </span>
              </div>
            </template>

            <!-- 提取结果 -->
            <template v-if="item.extractors?.length">
              <div class="detail-title">提取（{{ item.extractors.length }}）</div>
              <div v-for="(extractor, ei) in item.extractors" :key="ei" class="kv-row">
                <span class="kv-label mono">${{ extractor.refName }}</span>
                <span class="kv-value">
                  <el-tag :type="extractor.hit ? 'success' : 'warning'" size="small" effect="plain">
                    {{ extractor.hit ? '命中' : '未命中' }}
                  </el-tag>
                  <span class="expect-text mono">{{ extractor.value }}</span>
                </span>
              </div>
            </template>
          </div>
        </el-collapse-transition>
      </div>

      <el-empty v-if="!loading && !items.length" description="暂无调试结果" />
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import { debugScript } from '@/api/script'

/** 抽屉可见状态 */
const visible = ref(false)

/** 抽屉标题（一键调试 / 单接口调试） */
const title = ref('脚本调试')

/** 调试执行中 */
const loading = ref(false)

/** 调试结果明细（逐接口） */
const items = ref([])

/** 当前展开的接口下标（-1 全收起；默认自动展开第一个失败接口） */
const opened = ref(-1)

/** 全部通过（请求成功且断言通过）的接口数 */
const okCount = computed(() => items.value.filter((item) => item.ok).length)

/** 请求失败（异常或 HTTP ≥400）的接口数 */
const errorCount = computed(
  () => items.value.filter((item) => item.error || item.success === false).length
)

/** 请求成功但断言未全部通过的接口数 */
const assertFailCount = computed(
  () => items.value.filter((item) => !item.error && item.success && !item.ok).length
)

/**
 * 打开抽屉并执行调试：携带编辑器当前表单定义请求后端逐接口执行
 * @param {Object} formDef 表单定义（未保存的草稿也可调试；单接口调试时仅含目标接口）
 * @param {string} [drawerTitle] 抽屉标题（默认"脚本调试"，单接口场景传"调试接口"）
 */
async function open(formDef, drawerTitle = '脚本调试') {
  visible.value = true
  title.value = drawerTitle
  loading.value = true
  items.value = []
  opened.value = -1
  try {
    const res = await debugScript(formDef)
    items.value = res.data?.items || []
    // 自动展开第一个问题接口（无问题时展开第一个）
    const firstBad = items.value.findIndex((item) => !item.ok)
    opened.value = firstBad >= 0 ? firstBad : 0
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 方法徽标样式类（与编辑页 m-chip 同风格）
 * @param {string} method HTTP 方法
 * @returns {string} 样式类名
 */
function methodClass(method) {
  const m = (method || 'GET').toUpperCase()
  if (m === 'GET') {
    return 'm-get'
  }
  if (m === 'POST') {
    return 'm-post'
  }
  if (m === 'PUT' || m === 'PATCH') {
    return 'm-put'
  }
  if (m === 'DELETE') {
    return 'm-delete'
  }
  return 'm-other'
}

defineExpose({ open })
</script>

<style scoped>
.debug-body {
  min-height: 200px;
}

/* 摘要条 */
.summary-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius-sm);
  background-color: var(--pp-surface-2);
  margin-bottom: 12px;
}
.summary-item {
  font-size: 13px;
  font-weight: 600;
  color: var(--pp-text-primary);
}
.summary-item.ok {
  color: var(--pp-success);
}
.summary-item.warn {
  color: var(--pp-warning);
}
.summary-item.err {
  color: var(--pp-danger);
}
.summary-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--pp-text-placeholder);
}

/* 接口结果卡片 */
.debug-item {
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius-sm);
  margin-bottom: 10px;
  background-color: var(--pp-surface);
  overflow: hidden;
}
.debug-item.open {
  border-color: var(--el-color-primary-light-5);
}
.item-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
}
.item-head:hover {
  background-color: var(--pp-surface-2);
}
.item-caret {
  font-size: 12px;
  color: var(--pp-text-placeholder);
  transition: transform 0.18s ease;
  flex-shrink: 0;
}
.item-caret.open {
  transform: rotate(90deg);
}
.item-name {
  flex: 1;
  min-width: 80px;
  font-size: 13px;
  font-weight: 500;
  color: var(--pp-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.item-elapsed {
  font-size: 12px;
  color: var(--pp-text-secondary);
  flex-shrink: 0;
}

.item-detail {
  border-top: 1px solid var(--pp-border);
  padding: 14px 16px;
}

/* 分区标题与小块 */
.detail-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--pp-text-regular);
  margin: 12px 0 8px;
}
.detail-title:first-child {
  margin-top: 0;
}
.err-title {
  color: var(--pp-danger);
}
.detail-block {
  margin-bottom: 8px;
}
.block-label {
  font-size: 12px;
  color: var(--pp-text-secondary);
  margin-bottom: 6px;
}

/* 键值行 */
.kv-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 3px 0;
  font-size: 13px;
}
.kv-label {
  flex-shrink: 0;
  width: 110px;
  color: var(--pp-text-secondary);
  word-break: break-all;
}
.kv-value {
  flex: 1;
  min-width: 0;
  color: var(--pp-text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  word-break: break-all;
}
.expect-text {
  color: var(--pp-text-regular);
}

/* 请求/响应体代码块 */
.code-block {
  margin: 0;
  padding: 10px 12px;
  max-height: 320px;
  overflow: auto;
  background-color: var(--pp-surface-2);
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius-sm);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--pp-text-regular);
}
.error-text {
  color: var(--pp-danger);
}

/* 方法徽标（与编辑页同风格） */
.m-chip {
  flex-shrink: 0;
  font-family: var(--pp-font-mono);
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 5px;
  letter-spacing: 0.3px;
}
.m-get {
  background-color: var(--el-color-success-light-9);
  color: var(--pp-success);
}
.m-post {
  background-color: var(--el-color-primary-light-9);
  color: var(--pp-primary);
}
.m-put {
  background-color: var(--el-color-warning-light-9);
  color: var(--pp-warning);
}
.m-delete {
  background-color: var(--el-color-danger-light-9);
  color: var(--pp-danger);
}
.m-other {
  background-color: var(--pp-bg);
  color: var(--pp-text-secondary);
}

.mono {
  font-family: var(--pp-font-mono);
}
</style>
