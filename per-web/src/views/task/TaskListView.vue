<!--
  任务中心列表页：
  - 关键字（任务名/编号）与状态筛选、分页查询
  - 任务列表展示：任务号（等宽字体）/模式/执行方式（手动/定时+定时时间）/脚本版本/节点数/状态（按状态着色）/起止时间等
  - 行内操作：详情跳转、编辑（仅 CREATED，跳转全屏编辑页 /tasks/:id/edit）、启动（仅 CREATED 且非定时）、
    改为立即执行（CREATED 且 SCHEDULED，覆盖定时设置）、停止（PREPARING/RUNNING/STOPPING），均二次确认
  - 「新建压测任务」跳转全屏编辑页 /tasks/new（独立页面承载创建表单）
-->
<template>
  <div class="page">
    <!-- 搜索工具栏 -->
    <div class="page-card toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="query.keyword"
            placeholder="任务名称 / 任务编号"
            clearable
            class="w-220"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-select
            v-model="query.status"
            placeholder="全部状态"
            class="w-140"
            @change="handleSearch"
          >
            <el-option label="全部状态" value="" />
            <el-option
              v-for="(meta, key) in TASK_STATUS_META"
              :key="key"
              :label="meta.text"
              :value="key"
            />
          </el-select>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="handleReset">重置</el-button>
        </div>
        <el-button type="primary" :icon="Plus" @click="router.push('/tasks/new')">新建压测任务</el-button>
      </div>
    </div>

    <!-- 任务列表 -->
    <div class="page-card">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="任务号" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.taskNo || row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="任务名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="模式" width="110" align="center">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ TASK_MODE_META[row.mode] || row.mode || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行方式" width="170" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.triggerType === 'SCHEDULED' ? 'warning' : 'info'"
              effect="plain"
              size="small"
            >
              {{ row.triggerType === 'SCHEDULED' ? '定时' : '手动' }}
            </el-tag>
            <div v-if="row.triggerType === 'SCHEDULED' && row.scheduledStartTime" class="schedule-time">
              {{ row.scheduledStartTime }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="脚本" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.scriptName">{{ row.scriptName }} v{{ row.scriptVersion }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="节点数" width="80" align="center">
          <template #default="{ row }">{{ (row.nodeKeys || []).length }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="TASK_STATUS_META[row.status]?.type || 'info'" size="small">
              {{ TASK_STATUS_META[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column prop="createBy" label="创建人" width="110" align="center">
          <template #default="{ row }">{{ row.createBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'CREATED'"
              link
              type="primary"
              @click="goEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="row.status === 'RUNNING'"
              link
              type="warning"
              @click="goMonitor(row)"
            >
              监控
            </el-button>
            <el-button
              v-if="row.status === 'FINISHED'"
              link
              type="primary"
              @click="goReport(row)"
            >
              报告
            </el-button>
            <el-button
              v-if="row.status === 'CREATED' && row.triggerType !== 'SCHEDULED'"
              link
              type="success"
              @click="handleStart(row)"
            >
              启动
            </el-button>
            <!-- 定时任务（CREATED 且 SCHEDULED）：平台会按定时时间自动触发，此处提供「改为立即执行」入口覆盖定时 -->
            <el-button
              v-if="row.status === 'CREATED' && row.triggerType === 'SCHEDULED'"
              link
              type="warning"
              @click="handleStart(row)"
            >
              改为立即执行
            </el-button>
            <el-button
              v-if="['PREPARING', 'RUNNING', 'STOPPING'].includes(row.status)"
              link
              type="danger"
              @click="handleStop(row)"
            >
              停止
            </el-button>
            <!-- 复制：一键继承配置快速建新任务（未启动/终态显示） -->
            <el-button
              v-if="['CREATED', 'FINISHED', 'FAILED'].includes(row.status)"
              link
              type="primary"
              @click="handleCopy(row)"
            >
              复制
            </el-button>
            <!-- 删除：未启动或终态任务可删，连同报告与指标数据一并清理 -->
            <el-button
              v-if="['CREATED', 'FINISHED', 'FAILED'].includes(row.status)"
              link
              type="danger"
              @click="handleRemove(row)"
            >
              删除
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
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search, RefreshLeft, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  page as pageTasks,
  startTask,
  stopTask,
  removeTask,
  copyTask
} from '@/api/task'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

/** 任务状态元信息：展示文案与 tag 颜色 */
const TASK_STATUS_META = {
  CREATED: { text: '已创建', type: 'info' },
  PREPARING: { text: '准备中', type: 'warning' },
  RUNNING: { text: '运行中', type: 'success' },
  STOPPING: { text: '停止中', type: 'warning' },
  FINISHED: { text: '已完成', type: 'primary' },
  FAILED: { text: '失败', type: 'danger' }
}

/** 压测模式元信息：展示文案 */
const TASK_MODE_META = {
  CONCURRENT: '并发模式',
  FIXED_TPS: '固定TPS',
  STEPPED: '阶梯压测'
}

/** 搜索条件 */
const query = reactive({
  keyword: '',
  status: ''
})

/** 分页状态：当前页 / 每页条数 / 总记录数 */
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 列表数据与加载状态 */
const rows = ref([])
const loading = ref(false)

/**
 * 加载任务列表：根据搜索条件与分页参数请求后端
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (query.keyword && query.keyword.trim()) {
      params.keyword = query.keyword.trim()
    }
    if (query.status) {
      params.status = query.status
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
  query.status = ''
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
 * 跳转任务详情页
 * @param {Object} row 任务行数据
 */
function goDetail(row) {
  router.push(`/tasks/${row.id}`)
}

/**
 * 跳转全屏任务编辑页（仅 CREATED 任务，编辑保存后仍为 CREATED）
 * @param {Object} row 任务行数据
 */
function goEdit(row) {
  router.push(`/tasks/${row.id}/edit`)
}

/**
 * 跳转监控大屏（携带 taskId 查询参数）
 * @param {Object} row 任务行数据
 */
function goMonitor(row) {
  router.push({ path: '/monitor', query: { taskId: String(row.id) } })
}

/**
 * 跳转压测报告详情页
 * @param {Object} row 任务行数据
 */
function goReport(row) {
  router.push(`/reports/${row.id}`)
}

/**
 * 启动任务：二次确认后调用启动接口，成功后刷新列表；
 * 定时任务（SCHEDULED）调用同一启动接口会覆盖定时设置并立即执行（后端置 triggerType=MANUAL）
 * @param {Object} row 任务行数据
 */
async function handleStart(row) {
  const isScheduled = row.triggerType === 'SCHEDULED'
  try {
    await ElMessageBox.confirm(
      isScheduled
        ? `确认将任务「${row.name}」改为立即执行吗？原定时设置（${row.scheduledStartTime || '-'}）将被覆盖。`
        : `确认启动任务「${row.name}」开始压测吗？`,
      isScheduled ? '改为立即执行' : '启动确认',
      { type: 'warning', confirmButtonText: isScheduled ? '立即执行' : '启动', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await startTask(row.id)
    ElMessage.success(isScheduled ? '已改为立即执行，任务启动中' : '任务已启动')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 停止任务：二次确认后调用停止接口，成功后刷新列表
 * @param {Object} row 任务行数据
 */
async function handleStop(row) {
  try {
    await ElMessageBox.confirm(
      `确认停止任务「${row.name}」吗？已运行的压测将被终止。`,
      '停止确认',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await stopTask(row.id)
    ElMessage.success('停止指令已下发')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 一键复制任务：直接调用复制接口（配置/节点/占比/文件分发全部继承），
 * 成功后提示新任务名并回到第一页刷新（新任务 id 最大排在最前）
 * @param {Object} row 任务行数据
 */
async function handleCopy(row) {
  try {
    const res = await copyTask(row.id)
    ElMessage.success(`已复制为「${res.data?.name || row.name + '-副本'}」，可直接启动`)
    page.value = 1
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 删除任务：二次确认后调用删除接口（连同报告与指标数据一并清理），成功后刷新列表
 * @param {Object} row 任务行数据
 */
async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除任务「${row.name}」（${row.taskNo || row.id}）吗？其压测报告、监控指标、节点明细将一并删除且不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await removeTask(row.id)
    ElMessage.success('任务已删除')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

// 页面挂载后自动加载任务列表
onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.w-220 {
  width: 220px;
}

.w-140 {
  width: 140px;
}

.mono {
  font-family: var(--pp-font-mono);
  font-size: 13px;
}

/* 执行方式列：定时任务的时间展示（tag 下方小字） */
.schedule-time {
  margin-top: 4px;
  font-size: 12px;
  color: var(--pp-text-secondary);
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
