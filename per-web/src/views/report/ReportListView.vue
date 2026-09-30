<!--
  报告中心列表页（路由 /reports）：
  - 仅展示 FINISHED（已完成）任务的报告入口，复用任务分页接口（status=FINISHED）
  - 列：任务号 / 名称 / 模式 / 节点数 / 结束时间 / 操作「查看报告」跳转 /reports/{id}
  - 支持关键字搜索（回车/清空即查）与分页
-->
<template>
  <div class="page">
    <!-- 搜索工具栏 -->
    <div class="page-card toolbar-card">
      <el-input
        v-model="keyword"
        placeholder="搜索任务名称 / 任务编号"
        clearable
        :prefix-icon="Search"
        class="w-260"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
    </div>

    <!-- 已完成任务报告列表 -->
    <div class="page-card">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="任务号" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.taskNo || row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="任务名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="模式" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.mode === 'CONCURRENT'" effect="plain" size="small">并发模式</el-tag>
            <el-tag v-else-if="row.mode === 'FIXED_TPS'" effect="plain" size="small" type="warning">固定TPS</el-tag>
            <el-tag v-else-if="row.mode === 'STEPPED'" effect="plain" size="small" type="success">阶梯</el-tag>
            <el-tag v-else effect="plain" size="small">{{ row.mode || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="节点数" width="90" align="center">
          <template #default="{ row }">{{ (row.nodeKeys || []).length }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="180" align="center">
          <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :icon="TrendCharts" @click="goReport(row)">
              查看报告
            </el-button>
          </template>
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
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search, TrendCharts } from '@element-plus/icons-vue'
import { page as pageTasks } from '@/api/task'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

/** 搜索关键字 / 分页状态 / 列表数据 */
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const rows = ref([])
const loading = ref(false)

/**
 * 加载已完成任务列表（status=FINISHED）
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value, status: 'FINISHED' }
    if (keyword.value && keyword.value.trim()) {
      params.keyword = keyword.value.trim()
    }
    const res = await pageTasks(params)
    rows.value = res.data?.records || []
    total.value = Number(res.data?.total) || 0
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 搜索框回车/清空：重置到第一页并重新加载
 */
function handleSearch() {
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

/**
 * 跳转报告详情页
 * @param {Object} row 任务行数据
 */
function goReport(row) {
  router.push(`/reports/${row.id}`)
}

// 页面挂载后自动加载
onMounted(load)
</script>

<style scoped>
.toolbar-card {
  margin-bottom: 16px;
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
