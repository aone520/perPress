<!--
  审计日志页（仅 ADMIN 可见）：
  - 按关键字（匹配用户/动作）搜索 + 分页查询
  - 表格展示时间、用户、动作、详情、来源 IP
-->
<template>
  <div class="page">
    <!-- 搜索工具栏（page-card 全局卡片类：白底/细边框/圆角/淡阴影） -->
    <div class="page-card toolbar-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="关键字（用户 / 动作）"
          clearable
          :prefix-icon="Search"
          class="w-260"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="RefreshLeft" @click="handleReset">重置</el-button>
      </div>
    </div>

    <!-- 日志列表 -->
    <div class="page-card">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="用户" width="140">
          <template #default="{ row }">{{ row.username || '-' }}</template>
        </el-table-column>
        <el-table-column label="动作" width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.action || '-' }}</template>
        </el-table-column>
        <el-table-column label="详情" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.detail || '-' }}</template>
        </el-table-column>
        <el-table-column label="IP" width="140">
          <template #default="{ row }">{{ row.ip || '-' }}</template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="handleSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import { page as pageAuditLogs } from '@/api/audit'
import { formatDateTime } from '@/utils/format'

/** 搜索条件 */
const query = reactive({
  keyword: ''
})

/** 分页状态：当前页 / 每页条数 / 总记录数 */
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 列表数据与加载状态 */
const rows = ref([])
const loading = ref(false)

/**
 * 加载审计日志列表：根据关键字与分页参数请求后端
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (query.keyword && query.keyword.trim()) {
      params.keyword = query.keyword.trim()
    }
    const res = await pageAuditLogs(params)
    rows.value = res.data?.records || []
    total.value = Number(res.data?.total) || 0
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 查询按钮：重置到第一页并重新加载
 */
function handleSearch() {
  page.value = 1
  load()
}

/**
 * 重置按钮：清空搜索条件并回到第一页重新加载
 */
function handleReset() {
  query.keyword = ''
  page.value = 1
  load()
}

/**
 * 每页条数变化：回到第一页并重新加载
 */
function handleSizeChange() {
  page.value = 1
  load()
}

// 页面挂载后自动加载审计日志
onMounted(load)
</script>

<style scoped>
/* 工具栏卡片与列表卡片间距（与其它列表页一致） */
.toolbar-card {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.w-260 {
  width: 260px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
