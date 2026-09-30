<!--
  监控大屏页面（路由 /monitor，支持 ?taskId=）：
  - 无 taskId 时：展示任务选择卡片（近 20 条非 CREATED 任务下拉），选中后跳转 ?taskId=
  - 有 taskId 时：KPI 行（当前 TPS / 平均 RT / P95 / 错误率 / 活跃线程，附总请求数）
    + 2×2 四张 ECharts 曲线（TPS+错误数副轴、RT avg/p95/p99、错误率%、活跃线程），共用同一时间轴（HH:mm:ss）
  - 每 3 秒轮询 metrics；detail 接口检测到任务 FINISHED/FAILED 时停止轮询并提示"任务已结束，数据为最终态"
  - 页面离开清除定时器与 resize 监听并销毁图表实例；echarts 按需引入，容器随窗口自适应
-->
<template>
  <div class="page">
    <!-- 任务选择态：未携带 taskId -->
    <el-card v-if="!taskId" shadow="never" class="picker-card">
      <template #header>
        <span class="card-title">监控大屏 · 选择任务</span>
      </template>
      <el-empty v-if="!pickerLoading && !taskOptions.length" description="暂无可监控的任务" />
      <div v-else class="picker-body">
        <el-select
          v-model="pickedId"
          :loading="pickerLoading"
          filterable
          placeholder="请选择要监控的任务（近 20 条非「已创建」任务）"
          class="picker-select"
          @change="handlePick"
        >
          <el-option
            v-for="item in taskOptions"
            :key="item.id"
            :label="`${item.taskNo || item.id} ・ ${item.name}`"
            :value="item.id"
          >
            <div class="picker-option">
              <span class="mono">{{ item.taskNo || item.id }}</span>
              <span class="picker-option-name">{{ item.name }}</span>
              <el-tag :type="TASK_STATUS_META[item.status]?.type || 'info'" size="small">
                {{ TASK_STATUS_META[item.status]?.text || item.status }}
              </el-tag>
            </div>
          </el-option>
        </el-select>
        <div class="picker-tip">仅展示近 20 条非「已创建」状态的任务</div>
      </div>
    </el-card>

    <!-- 监控态：携带 taskId -->
    <template v-else>
      <!-- 头部：任务信息 + 轮询状态 -->
      <el-card shadow="never" class="head-card">
        <div class="head-bar">
          <div class="head-info">
            <span class="mono head-no">{{ task?.taskNo || taskId }}</span>
            <span class="head-name">{{ task?.name || '任务加载中…' }}</span>
            <el-tag v-if="task" :type="TASK_STATUS_META[task.status]?.type || 'info'">
              {{ TASK_STATUS_META[task.status]?.text || task.status }}
            </el-tag>
            <el-tag v-if="finished" type="info" effect="plain">数据为最终态</el-tag>
            <el-tag v-else type="success" effect="plain">实时刷新中（3s）</el-tag>
          </div>
          <el-button :icon="SwitchButton" @click="switchTask">切换任务</el-button>
        </div>
      </el-card>

      <!-- KPI 行：5 张指标卡（flex 均分，避免 24 栅格除不尽溢出） -->
      <el-row :gutter="12" class="kpi-row">
        <el-col v-for="card in kpiCards" :key="card.label" class="kpi-col">
          <el-card shadow="never" class="kpi-card">
            <div class="kpi-label">{{ card.label }}</div>
            <div class="kpi-value" :style="{ color: card.color }">{{ card.value }}</div>
            <div class="kpi-sub">{{ card.sub }}</div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 图表区：2×2 四张曲线 -->
      <el-row :gutter="12">
        <el-col :span="12">
          <el-card shadow="never" class="chart-card">
            <div class="chart-title">TPS</div>
            <div ref="tpsChartRef" class="chart-box" />
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" class="chart-card">
            <div class="chart-title">响应时间（ms）</div>
            <div ref="rtChartRef" class="chart-box" />
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" class="chart-card">
            <div class="chart-title">错误率（%）</div>
            <div ref="errChartRef" class="chart-box" />
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never" class="chart-card">
            <div class="chart-title">活跃线程</div>
            <div ref="threadChartRef" class="chart-box" />
          </el-card>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { page as pageTasks, detail as taskDetail } from '@/api/task'
import { getTaskMetrics } from '@/api/metric'
import { formatClock } from '@/utils/format'

// echarts 按需注册：折线 / 柱状 + tooltip + legend + grid + canvas 渲染器
echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const route = useRoute()
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

/** 任务终态集合：到达后停止轮询 */
const TERMINAL_STATUSES = ['FINISHED', 'FAILED']

/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 3000

/** 连续拉取失败上限：达到后停止自动刷新，避免错误提示刷屏 */
const MAX_FAIL_COUNT = 3

/** 当前监控的任务 ID（来自 ?taskId=） */
const taskId = computed(() => route.query.taskId || '')

/* ---------------- 任务选择态 ---------------- */

/** 选择态：下拉选项 / 已选值 / 加载中 */
const taskOptions = ref([])
const pickedId = ref(null)
const pickerLoading = ref(false)

/**
 * 加载近 20 条非 CREATED 任务作为下拉选项（拉取最近 50 条后过滤）
 */
async function loadTaskOptions() {
  pickerLoading.value = true
  try {
    const res = await pageTasks({ page: 1, size: 50 })
    taskOptions.value = (res.data?.records || [])
      .filter((item) => item.status !== 'CREATED')
      .slice(0, 20)
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    pickerLoading.value = false
  }
}

/**
 * 选中任务后跳转监控态（?taskId=）
 * @param {number|string} id 任务 ID
 */
function handlePick(id) {
  router.push({ path: '/monitor', query: { taskId: String(id) } })
}

/**
 * 返回任务选择态：清空 taskId 查询参数
 */
function switchTask() {
  stopPolling()
  router.push({ path: '/monitor' })
}

/* ---------------- 监控态 ---------------- */

/** 任务详情（用于头部信息与终态判断） */
const task = ref(null)

/** 指标数据：series / samplers / total */
const metrics = reactive({ series: [], samplers: [], total: null })

/** 任务是否已到终态（FINISHED/FAILED） */
const finished = ref(false)

/** 轮询定时器句柄与连续失败计数 */
let pollTimer = null
let failCount = 0

/** 四张图表的容器 ref 与实例句柄 */
const tpsChartRef = ref(null)
const rtChartRef = ref(null)
const errChartRef = ref(null)
const threadChartRef = ref(null)
let chartInstances = []

/**
 * 计算序列最新一个采样点
 * @returns {Object|null} 最新采样点，无数据时返回 null
 */
const latest = computed(() => {
  const list = metrics.series || []
  return list.length ? list[list.length - 1] : null
})

/**
 * 全序列平均响应时间（对各窗口 avgMs 求均值，作为平均 RT 的近似）
 * @returns {string} 保留一位小数的毫秒值，无数据返回 '-'
 */
const avgRt = computed(() => {
  const list = metrics.series || []
  if (!list.length) {
    return '-'
  }
  const sum = list.reduce((acc, item) => acc + (Number(item.avgMs) || 0), 0)
  return (sum / list.length).toFixed(1)
})

/**
 * 总请求数 / 总错误数（优先取 total 字段，缺失时由序列累加兜底）
 */
const totalCount = computed(() => {
  if (metrics.total && Number.isFinite(Number(metrics.total.count))) {
    return Number(metrics.total.count) || 0
  }
  return (metrics.series || []).reduce((acc, item) => acc + (Number(item.tps) || 0), 0)
})
const totalError = computed(() => {
  if (metrics.total && Number.isFinite(Number(metrics.total.errorCount))) {
    return Number(metrics.total.errorCount) || 0
  }
  return (metrics.series || []).reduce((acc, item) => acc + (Number(item.errorCount) || 0), 0)
})

/**
 * 累计错误率（%）：总错误 / 总请求
 */
const errorRate = computed(() => {
  if (!totalCount.value) {
    return '0.00'
  }
  return ((totalError.value / totalCount.value) * 100).toFixed(2)
})

/** KPI 行 6 张卡的展示数据（APDEX 为行业标准满意度指数，阈值 500/1500ms） */
const kpiCards = computed(() => [
  {
    label: '当前 TPS',
    value: latest.value ? (Number(latest.value.tps) || 0).toLocaleString() : '-',
    sub: '最新采样窗口',
    color: '#409eff'
  },
  {
    label: '平均 RT',
    value: avgRt.value === '-' ? '-' : `${avgRt.value} ms`,
    sub: '全窗口均值',
    color: '#67c23a'
  },
  {
    label: 'P95',
    value: latest.value ? `${Number(latest.value.p95) || 0} ms` : '-',
    sub: '最新采样窗口',
    color: '#e6a23c'
  },
  {
    label: 'APDEX',
    value: metrics.total?.apdex ?? '-',
    sub: '满意度（≤500ms 满分）',
    color: '#9254de'
  },
  {
    label: '错误率',
    value: `${errorRate.value}%`,
    sub: `总请求 ${(Number(totalCount.value) || 0).toLocaleString()} 次`,
    color: '#f56c6c'
  },
  {
    label: '活跃线程',
    value: latest.value ? Number(latest.value.threads) || 0 : '-',
    sub: '最新采样窗口',
    color: '#909399'
  }
])

/**
 * 根据序列估算各窗口请求数（tps × 窗口秒数），用于累计错误率曲线
 * @param {Array} series 指标序列
 * @returns {Array} 每个窗口的请求数
 */
function windowCounts(series) {
  return series.map((item, index) => {
    const dt = index > 0 ? Math.max(1, (Number(item.t) || 0) - (Number(series[index - 1].t) || 0)) : 1
    return (Number(item.tps) || 0) * dt
  })
}

/**
 * 构建基础 xAxis 配置：类目轴 + 时间格式化为 HH:mm:ss（兼容秒/毫秒时间戳）+ 重叠标签自动隐藏
 * @param {Array} series 指标序列
 * @returns {Object} ECharts xAxis 选项
 */
function baseXAxis(series) {
  return {
    type: 'category',
    data: series.map((i) => formatClock(i.t)),
    axisLabel: { hideOverlap: true }
  }
}

/**
 * 构建基础 grid 配置（四张图共用版式）
 * @returns {Object} ECharts grid 选项
 */
function baseGrid() {
  return { left: 52, right: 52, top: 42, bottom: 28 }
}

/**
 * 构建基础 tooltip 配置（轴触发，附单位）
 * @param {string} [unit] 数值单位
 * @returns {Object} ECharts tooltip 选项
 */
function baseTooltip(unit = '') {
  return {
    trigger: 'axis',
    valueFormatter: (value) => (value == null ? '-' : `${value}${unit}`)
  }
}

/**
 * 渲染 TPS 图：仅 TPS 折线（错误信息由独立错误率图承载，避免混轴干扰读数）
 * @param {Array} series 指标序列
 */
function renderTpsChart(series) {
  chartInstances[0]?.setOption(
    {
      tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
      legend: { top: 4, data: ['TPS'] },
      grid: baseGrid(),
      xAxis: baseXAxis(series),
      yAxis: [{ type: 'value', name: 'TPS', minInterval: 1 }],
      series: [
        {
          name: 'TPS',
          type: 'line',
          smooth: false,
          showSymbol: false,
          itemStyle: { color: '#409eff' },
          data: series.map((i) => Number(i.tps) || 0)
        }
      ]
    },
    true
  )
}

/**
 * 渲染 RT 图：avg / p90 / p95 / p99 四条折线（直线连接不平滑，真实呈现毛刺与台阶）
 * @param {Array} series 指标序列
 */
function renderRtChart(series) {
  const mk = (name, key, color) => ({
    name,
    type: 'line',
    smooth: false,
    showSymbol: false,
    itemStyle: { color },
    data: series.map((i) => (i[key] == null ? null : Number(i[key])))
  })
  chartInstances[1]?.setOption(
    {
      tooltip: baseTooltip(' ms'),
      legend: { top: 4, data: ['Avg', 'P90', 'P95', 'P99'] },
      grid: baseGrid(),
      xAxis: baseXAxis(series),
      yAxis: { type: 'value', name: 'ms' },
      series: [
        mk('Avg', 'avgMs', '#409eff'),
        mk('P90', 'p90', '#67c23a'),
        mk('P95', 'p95', '#e6a23c'),
        mk('P99', 'p99', '#f56c6c')
      ]
    },
    true
  )
}

/**
 * 渲染错误率图：累计错误率（%）折线
 * @param {Array} series 指标序列
 */
function renderErrChart(series) {
  const counts = windowCounts(series)
  let sumCount = 0
  let sumError = 0
  const rates = series.map((item, index) => {
    sumCount += counts[index]
    sumError += Number(item.errorCount) || 0
    return sumCount > 0 ? Number(((sumError / sumCount) * 100).toFixed(3)) : 0
  })
  chartInstances[2]?.setOption(
    {
      tooltip: baseTooltip(' %'),
      legend: { top: 4, data: ['累计错误率'] },
      grid: baseGrid(),
      xAxis: baseXAxis(series),
      yAxis: { type: 'value', name: '%', axisLabel: { formatter: '{value}%' } },
      series: [
        {
          name: '累计错误率',
          type: 'line',
          smooth: true,
          areaStyle: { opacity: 0.12 },
          itemStyle: { color: '#f56c6c' },
          data: rates
        }
      ]
    },
    true
  )
}

/**
 * 渲染活跃线程图：阶梯折线
 * @param {Array} series 指标序列
 */
function renderThreadChart(series) {
  chartInstances[3]?.setOption(
    {
      tooltip: baseTooltip(''),
      legend: { top: 4, data: ['活跃线程'] },
      grid: baseGrid(),
      xAxis: baseXAxis(series),
      yAxis: { type: 'value', name: '线程', minInterval: 1 },
      series: [
        {
          name: '活跃线程',
          type: 'line',
          step: 'middle',
          itemStyle: { color: '#67c23a' },
          data: series.map((i) => (i.threads == null ? null : Number(i.threads)))
        }
      ]
    },
    true
  )
}

/**
 * 用最新指标序列刷新全部 KPI 与四张图表
 */
function renderAll() {
  const series = metrics.series || []
  renderTpsChart(series)
  renderRtChart(series)
  renderErrChart(series)
  renderThreadChart(series)
}

/**
 * 拉取实时指标并刷新界面
 * @param {boolean} silent 是否静默（轮询场景不再额外处理错误 UI）
 */
async function loadMetrics(silent = false) {
  try {
    const res = await getTaskMetrics(taskId.value)
    const data = res.data || {}
    metrics.series = data.series || []
    metrics.samplers = data.samplers || []
    metrics.total = data.total || null
    failCount = 0
    renderAll()
  } catch {
    if (!silent) {
      return
    }
    // 轮询场景：连续失败达上限后停止自动刷新，避免错误提示刷屏
    failCount += 1
    if (failCount >= MAX_FAIL_COUNT) {
      stopPolling()
      ElMessage.warning('指标拉取连续失败，已停止自动刷新')
    }
  }
}

/**
 * 拉取任务详情：更新头部信息；到达终态时停止轮询并提示
 */
async function loadTask() {
  try {
    const res = await taskDetail(taskId.value)
    task.value = res.data || null
    if (task.value && TERMINAL_STATUSES.includes(task.value.status)) {
      if (!finished.value) {
        finished.value = true
        stopPolling()
        ElMessage.info('任务已结束，数据为最终态')
      }
    }
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 单次轮询：并行拉取指标与任务详情
 */
async function pollOnce() {
  if (document.visibilityState === 'hidden' || finished.value) {
    return
  }
  await Promise.all([loadMetrics(true), loadTask()])
}

/**
 * 启动 3 秒轮询
 */
function startPolling() {
  stopPolling()
  pollTimer = setInterval(pollOnce, POLL_INTERVAL)
}

/**
 * 停止轮询：清除定时器
 */
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * 初始化四张图表实例（容器在监控态模板渲染后才存在，需懒初始化）
 */
function ensureCharts() {
  ;[tpsChartRef, rtChartRef, errChartRef, threadChartRef].forEach((item, index) => {
    if (item.value && !chartInstances[index]) {
      chartInstances[index] = echarts.init(item.value)
    }
  })
}

/**
 * 销毁全部图表实例（离开监控态 / 卸载页面时调用）
 */
function disposeCharts() {
  chartInstances.forEach((chart) => chart?.dispose())
  chartInstances = []
}

/**
 * 初始化监控态：重置数据、初始化图表、拉取详情与指标，非终态开启轮询
 */
async function setupMonitor() {
  finished.value = false
  failCount = 0
  task.value = null
  metrics.series = []
  metrics.samplers = []
  metrics.total = null
  await nextTick()
  ensureCharts()
  renderAll()
  await Promise.all([loadTask(), loadMetrics(false)])
  if (!finished.value) {
    startPolling()
  }
}

/**
 * 窗口尺寸变化：四张图表自适应
 */
function handleResize() {
  chartInstances.forEach((chart) => chart?.resize())
}

// 监控目标变化（进入页面 / 切换任务）：重新初始化监控态或回到选择态
watch(
  taskId,
  (value) => {
    if (value) {
      setupMonitor()
    } else {
      stopPolling()
      disposeCharts()
      loadTaskOptions()
    }
  },
  { immediate: false }
)

// 页面挂载：注册 resize 监听并进入对应状态（图表初始化由 setupMonitor 懒执行）
onMounted(() => {
  window.addEventListener('resize', handleResize)
  if (taskId.value) {
    setupMonitor()
  } else {
    loadTaskOptions()
  }
})

// 页面离开：停止轮询、移除 resize 监听并销毁图表实例
onUnmounted(() => {
  stopPolling()
  window.removeEventListener('resize', handleResize)
  disposeCharts()
})
</script>

<style scoped>
.picker-card {
  max-width: 720px;
  margin: 60px auto 0;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.picker-body {
  padding: 12px 0;
}

.picker-select {
  width: 100%;
}

.picker-option {
  display: flex;
  align-items: center;
  gap: 10px;
}

.picker-option-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}

.head-card {
  margin-bottom: 12px;
}

.head-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-info {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.head-no {
  font-size: 13px;
  color: #606266;
}

.head-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-row {
  margin-bottom: 12px;
}

/* KPI 卡等宽均分（Element Plus 栅格 24 列无法被 5 整除，改用 flex） */
.kpi-col {
  flex: 1 1 0;
  min-width: 0;
}

.kpi-card {
  text-align: center;
}

.kpi-label {
  font-size: 13px;
  color: #909399;
}

.kpi-value {
  margin: 8px 0 4px;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}

.kpi-sub {
  font-size: 12px;
  color: #c0c4cc;
}

.chart-card {
  margin-bottom: 12px;
}

.chart-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.chart-box {
  width: 100%;
  height: 300px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
}
</style>
