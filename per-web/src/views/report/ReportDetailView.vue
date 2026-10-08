<!--
  报告详情页（路由 /reports/:id）：自上而下 8 个区块
  ① 报告头 el-descriptions：任务号/名称/模式/节点数/起止/时长/是否已固化
  ② 全局 KPI 卡：总请求/总错误/错误率/平均TPS/峰值TPS/吞吐KB/s/峰值线程
  ③ 响应时间分位数：一行表格（min/avg/p50~p999/max）+ RT 趋势曲线（avg/p95/p99）
  ④ TPS 趋势曲线：tps 柱 + errorCount 副轴
  ⑤ 事务明细表（可排序、不分页）
  ⑥ 节点明细表：nodeKey 前 8 位/请求/TPS/错误/Avg/P95/P99/流量
  ⑦ 错误分析：错误码分布 + TOP 错误事务 + 错误样本明细 + 错误时间分布曲线
  ⑧ 长尾与慢事务：按 p99 降序，>1000ms 行标红
  顶部「导出」按钮下载 HTML 报告文件（report-{taskNo}.html），「打印」按钮调起 window.print()；
  页面卸载时清理图表与 resize 监听
-->
<template>
  <div class="page">
    <!-- 顶部：返回 + 打印 + 导出 -->
    <div class="page-header">
      <span class="page-title">压测报告</span>
      <div class="header-actions">
        <el-button :icon="Printer" @click="handlePrint">打印</el-button>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="handleExport">
          导出
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="report-wrap">
      <template v-if="report">
        <!-- ① 报告头 -->
        <div class="page-card block-card">
          <div class="card-title">报告头</div>
          <el-descriptions :column="4" border>
            <el-descriptions-item label="任务号">
              <span class="mono">{{ summary.taskNo || route.params.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="任务名称">{{ summary.name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模式">
              <el-tag effect="plain">{{ MODE_META[summary.mode]?.text || summary.mode || '-' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="节点数">{{ summary.nodeCount ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">
              {{ formatDateTime(summary.startTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="结束时间">
              {{ formatDateTime(summary.endTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="时长">
              {{ formatDuration(summary.durationSeconds) }}
            </el-descriptions-item>
            <el-descriptions-item label="是否已固化">
              <el-tag :type="report.finalized ? 'success' : 'warning'" size="small">
                {{ report.finalized ? '已固化' : '未固化' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- ② 全局 KPI -->
        <div class="page-card block-card">
          <div class="card-title">全局 KPI</div>
          <el-row :gutter="12" class="kpi-row">
            <el-col v-for="card in kpiCards" :key="card.label" class="kpi-col">
              <div class="kpi-box">
                <div class="kpi-label">{{ card.label }}</div>
                <div class="kpi-value" :style="{ color: card.color }">{{ card.value }}</div>
              </div>
            </el-col>
          </el-row>
        </div>

        <!-- ③ 响应时间分位数 + RT 趋势 -->
        <div class="page-card block-card">
          <div class="card-title">响应时间（ms）</div>
          <el-table :data="[rtRow]" size="small">
            <el-table-column
              v-for="col in RT_COLS"
              :key="col.prop"
              :prop="col.prop"
              :label="col.label"
              align="center"
            >
              <template #default="{ row }">{{ row[col.prop] }}</template>
            </el-table-column>
          </el-table>
          <div ref="rtChartRef" class="chart-box" />
        </div>

        <!-- ④ TPS 趋势 -->
        <div class="page-card block-card">
          <div class="card-title">TPS 趋势</div>
          <div ref="tpsChartRef" class="chart-box" />
        </div>

        <!-- ⑤ 事务明细 -->
        <div class="page-card block-card">
          <div class="card-title">事务明细（{{ samplers.length }}）</div>
          <el-table :data="samplers" size="small">
            <el-table-column prop="label" label="事务" min-width="220" show-overflow-tooltip fixed />
            <el-table-column prop="count" label="请求数" width="100" align="right" sortable />
            <el-table-column label="错误率" width="100" align="right" sortable :sort-method="sortBy('errorRate')">
              <template #default="{ row }">{{ pct(row.errorRate) }}</template>
            </el-table-column>
            <el-table-column prop="tps" label="平均TPS" width="100" align="right" sortable />
            <el-table-column label="峰值TPS" width="100" align="right" sortable :sort-method="sortBy('peakTps')">
              <template #default="{ row }">{{ row.peakTps ?? '-' }}</template>
            </el-table-column>
            <el-table-column prop="minMs" label="Min" width="80" align="right" sortable />
            <el-table-column prop="avgMs" label="Avg" width="80" align="right" sortable />
            <el-table-column prop="p90" label="P90" width="80" align="right" sortable />
            <el-table-column prop="p95" label="P95" width="80" align="right" sortable />
            <el-table-column prop="p99" label="P99" width="80" align="right" sortable />
            <el-table-column prop="maxMs" label="Max" width="80" align="right" sortable />
          </el-table>
        </div>

        <!-- ⑥ 节点明细 -->
        <div class="page-card block-card">
          <div class="card-title">节点明细（{{ nodes.length }}）</div>
          <el-table :data="nodes" size="small">
            <el-table-column label="节点" min-width="150">
              <template #default="{ row }">
                <!-- 优先显示主机名（IP 副行），节点已被删除等场景回退 nodeKey 缩略 -->
                <el-tooltip :content="`nodeKey: ${row.nodeKey}`" placement="top" :disabled="!row.nodeKey">
                  <div class="node-cell">
                    <span class="node-name">{{ row.hostname || shortNodeKey(row.nodeKey) }}</span>
                    <span v-if="row.ip" class="node-ip">{{ row.ip }}</span>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column prop="count" label="请求数" width="110" align="right" />
            <el-table-column prop="tps" label="平均TPS" width="100" align="right" />
            <el-table-column prop="errorCount" label="错误数" width="100" align="right" />
            <el-table-column prop="avgMs" label="Avg(ms)" width="100" align="right" />
            <el-table-column prop="p95" label="P95(ms)" width="100" align="right" />
            <el-table-column prop="p99" label="P99(ms)" width="100" align="right" />
            <el-table-column label="流量" min-width="110" align="right">
              <template #default="{ row }">{{ formatBytes(row.bytes) }}</template>
            </el-table-column>
            <!-- 压力机资源峰值（心跳通道采样；历史任务无数据时显示 -） -->
            <el-table-column label="CPU峰值" width="90" align="right">
              <template #default="{ row }">{{ row.cpuPeak != null ? row.cpuPeak + '%' : '-' }}</template>
            </el-table-column>
            <el-table-column label="MEM峰值" width="90" align="right">
              <template #default="{ row }">{{ row.memPeak != null ? row.memPeak + '%' : '-' }}</template>
            </el-table-column>
            <el-table-column label="网络峰值" width="100" align="right">
              <template #default="{ row }">
                {{ row.netPeakBps != null ? ((row.netPeakBps * 8) / 1e6).toFixed(2) + ' Mbps' : '-' }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- ⑥+ 压力机资源曲线（任务期间心跳采样，TPS 上不去时可同屏判断压力机瓶颈） -->
        <div v-if="hasNodeResources" class="page-card block-card">
          <div class="card-title">压力机资源</div>
          <el-row :gutter="12">
            <el-col :span="8">
              <div class="sub-title">CPU 使用率（%）</div>
              <div ref="nodeCpuChartRef" class="chart-box" />
            </el-col>
            <el-col :span="8">
              <div class="sub-title">内存使用率（%）</div>
              <div ref="nodeMemChartRef" class="chart-box" />
            </el-col>
            <el-col :span="8">
              <div class="sub-title">网络带宽（Mbps，收+发合计，悬浮看分解）</div>
              <div ref="nodeNetChartRef" class="chart-box" />
            </el-col>
          </el-row>
        </div>

        <!-- ⑦ 错误分析 -->
        <div class="page-card block-card">
          <div class="card-title">错误分析</div>
          <el-row :gutter="12">
            <el-col :span="8">
              <div class="sub-title">错误码分布</div>
              <el-table :data="errors.byCode" size="small" max-height="260">
                <el-table-column prop="code" label="响应码" align="center">
                  <template #default="{ row }">
                    <span class="mono">{{ row.code }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="count" label="次数" align="right" />
              </el-table>
            </el-col>
            <el-col :span="8">
              <div class="sub-title">TOP 错误事务</div>
              <el-table :data="errors.topSamplers" size="small" max-height="260">
                <el-table-column prop="label" label="事务" min-width="140" show-overflow-tooltip />
                <el-table-column prop="errorCount" label="错误数" width="90" align="right" />
                <el-table-column label="错误率" width="90" align="right">
                  <template #default="{ row }">{{ pct(row.errorRate) }}</template>
                </el-table-column>
              </el-table>
            </el-col>
            <el-col :span="8">
              <div class="sub-title">错误时间分布</div>
              <div ref="errChartRef" class="chart-box-small" />
            </el-col>
          </el-row>
          <div class="sub-title">错误样本明细</div>
          <el-table :data="errors.samples" size="small" max-height="320">
            <el-table-column label="时间" width="180" align="center">
              <template #default="{ row }">
                {{ formatDateTime(row.ts ?? row.createTime) }}
              </template>
            </el-table-column>
            <el-table-column prop="sampler" label="事务" min-width="200" show-overflow-tooltip />
            <el-table-column prop="responseCode" label="响应码" width="100" align="center">
              <template #default="{ row }">
                <span class="mono">{{ row.responseCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="错误信息" min-width="260">
              <template #default="{ row }">
                <el-tooltip :content="row.message" placement="top" :disabled="!row.message">
                  <span class="error-text">{{ row.message || '-' }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- ⑧ 长尾与慢事务 -->
        <div class="page-card block-card">
          <div class="card-title">长尾与慢事务（按 P99 降序，&gt;1000ms 标红）</div>
          <el-table :data="slowSamplers" size="small" :row-class-name="slowRowClass">
            <el-table-column prop="label" label="事务" min-width="240" show-overflow-tooltip />
            <el-table-column label="P95(ms)" width="110" align="right">
              <template #default="{ row }">{{ num(row.p95) }}</template>
            </el-table-column>
            <el-table-column label="P99(ms)" width="110" align="right" sortable :sort-method="sortBy('p99')">
              <template #default="{ row }">{{ num(row.p99) }}</template>
            </el-table-column>
            <el-table-column label="P999(ms)" width="110" align="right">
              <template #default="{ row }">{{ num(row.p999) }}</template>
            </el-table-column>
            <el-table-column label="错误率" width="110" align="right">
              <template #default="{ row }">{{ pct(row.errorRate) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <el-empty v-else-if="!loading" description="报告不存在或任务尚未生成报告" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Download, Printer } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { exportReport, getTaskReport } from '@/api/metric'
import { formatBytes, formatClock, formatDateTime } from '@/utils/format'
import {
  CHART_COLORS,
  CHART_GRID,
  chartCategoryAxis,
  chartLegend,
  chartLine,
  chartTooltip,
  chartValueAxis
} from '@/utils/chartTheme'

// echarts 按需注册：折线 / 柱状 + tooltip + legend + grid + canvas 渲染器
echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const route = useRoute()

/** 压测模式元信息：展示文案 */
const MODE_META = {
  CONCURRENT: { text: '并发模式' },
  FIXED_TPS: { text: '固定TPS' },
  STEPPED: { text: '阶梯' }
}

/** 分位数表的列定义（min/avg/p50~p999/max） */
const RT_COLS = [
  { prop: 'min', label: 'Min' },
  { prop: 'avg', label: 'Avg' },
  { prop: 'p50', label: 'P50' },
  { prop: 'p75', label: 'P75' },
  { prop: 'p90', label: 'P90' },
  { prop: 'p95', label: 'P95' },
  { prop: 'p99', label: 'P99' },
  { prop: 'p999', label: 'P999' },
  { prop: 'max', label: 'Max' }
]

/** 报告数据 / 加载状态 */
const report = ref(null)
const loading = ref(false)

/** 报告导出中状态 */
const exporting = ref(false)

/** 三张图表容器 ref 与实例句柄 */
const rtChartRef = ref(null)
const tpsChartRef = ref(null)
const errChartRef = ref(null)
/** 压力机资源双图容器 ref（有采样数据才渲染） */
const nodeCpuChartRef = ref(null)
const nodeMemChartRef = ref(null)
const nodeNetChartRef = ref(null)
let rtChart = null
let tpsChart = null
let errChart = null
let nodeCpuChart = null
let nodeMemChart = null
let nodeNetChart = null

/** 报告摘要（无数据时给空对象兜底） */
const summary = computed(() => report.value?.summary || {})

/** 时间序列 / 事务明细 / 节点明细（samplers 补算错误率，后端分区未携带时由 errorCount/count 计算） */
const series = computed(() => report.value?.series || [])
const samplers = computed(() => withErrorRate(report.value?.samplers || []))
const nodes = computed(() => report.value?.nodes || [])

/** 是否存在压力机资源采样（历史任务无数据时隐藏资源图区块） */
const hasNodeResources = computed(() =>
  nodes.value.some((row) => Array.isArray(row.resources) && row.resources.length)
)

/**
 * 为统计行补算错误率（%）：后端未返回 errorRate 时由 errorCount/count 计算，
 * 使事务明细的错误率列展示与排序可用（无样本返回 null，展示为 -）
 *
 * @param {Array<Object>} rows 统计行列表（含 count/errorCount）
 * @returns {Array<Object>} 补充 errorRate 后的行列表
 */
function withErrorRate(rows) {
  return rows.map((row) => {
    if (row?.errorRate !== null && row?.errorRate !== undefined && row?.errorRate !== '') {
      return row
    }
    const count = Number(row?.count) || 0
    return { ...row, errorRate: count ? ((Number(row?.errorCount) || 0) * 100) / count : null }
  })
}

/** 错误分析数据（byCode / topSamplers / samples / timeline） */
const errors = computed(() => report.value?.errors || { byCode: [], topSamplers: [], samples: [], timeline: [] })

/** 长尾与慢事务：按 p99 降序 */
const slowSamplers = computed(() =>
  [...samplers.value].sort((a, b) => (Number(b.p99) || 0) - (Number(a.p99) || 0))
)

/** 分位数单行表数据：空值展示 '-' */
const rtRow = computed(() => {
  const rt = summary.value.rt || {}
  const row = {}
  RT_COLS.forEach((col) => {
    row[col.prop] = rt[col.prop] == null ? '-' : rt[col.prop]
  })
  return row
})

/** 吞吐 KB/s 纯数值：接收总 KB ÷ 时长秒（时长为 0 时展示 '-'，单位放 KPI 标题） */
const throughputKbps = computed(() => {
  const kb = Number(summary.value.recvTotalKB)
  const seconds = Number(summary.value.durationSeconds)
  if (!Number.isFinite(kb) || !Number.isFinite(seconds) || seconds <= 0) {
    return '-'
  }
  return kb / seconds
})

/** 全局 KPI 8 张卡：单位统一放标题括号内，值只显示纯数值（避免打印换行）；配色走 chartTheme 低饱和色板 */
const kpiCards = computed(() => [
  { label: 'APDEX', value: summary.value.apdex ?? '-', color: CHART_COLORS.violet },
  { label: '总请求', value: num(summary.value.totalCount), color: CHART_COLORS.primary },
  { label: '总错误', value: num(summary.value.totalErrorCount), color: CHART_COLORS.rose },
  { label: '错误率 (%)', value: fixed(summary.value.errorRate), color: CHART_COLORS.rose },
  { label: '平均 TPS', value: num(summary.value.avgTps), color: CHART_COLORS.primary },
  { label: '峰值 TPS', value: num(summary.value.peakTps), color: CHART_COLORS.teal },
  { label: '吞吐 (KB/s)', value: fixed(throughputKbps.value), color: CHART_COLORS.amber },
  { label: '峰值线程', value: num(summary.value.peakThreads), color: CHART_COLORS.slate }
])

/**
 * 数值定长展示（不带单位，空值返回 '-'，默认两位小数）
 *
 * @param {number|string} value 数值
 * @returns {string} 纯数值文本
 */
function fixed(value, digits = 2) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return String(value)
  }
  return n.toFixed(digits)
}

/**
 * 数值展示：空值返回 '-'，否则千分位格式化
 * @param {number|string} value 数值
 * @returns {string} 展示文本
 */
function num(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return String(value)
  }
  return n.toLocaleString(undefined, { maximumFractionDigits: 2 })
}

/**
 * 百分比展示：空值返回 '-'，否则保留两位小数并加 %
 * @param {number|string} value 百分比数值
 * @returns {string} 展示文本
 */
function pct(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return String(value)
  }
  return `${n.toFixed(2)}%`
}

/**
 * 时长展示：秒转换为「X 分 Y 秒」形式
 * @param {number} seconds 时长（秒）
 * @returns {string} 展示文本
 */
function formatDuration(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n < 0) {
    return '-'
  }
  const m = Math.floor(n / 60)
  const s = Math.round(n % 60)
  return m > 0 ? `${m} 分 ${s} 秒` : `${s} 秒`
}

/**
 * 缩略展示 nodeKey：超过 8 位时取前 8 位加省略号
 * @param {string} key 节点 nodeKey
 * @returns {string} 缩略后的 nodeKey
 */
function shortNodeKey(key) {
  if (!key) {
    return '-'
  }
  return key.length > 8 ? `${key.slice(0, 8)}…` : key
}

/**
 * 生成数值字段排序方法（用于非直接数值列，如错误率）
 * @param {string} key 排序字段名
 * @returns {Function} el-table sort-method
 */
function sortBy(key) {
  return (a, b) => (Number(a[key]) || 0) - (Number(b[key]) || 0)
}

/**
 * 长尾表行样式：p99 > 1000ms 标红
 * @param {Object} param0 行上下文
 * @returns {string} 行 class 名
 */
function slowRowClass({ row }) {
  return Number(row.p99) > 1000 ? 'slow-row' : ''
}

/**
 * 构建基础 xAxis 配置：时间格式化为 HH:mm:ss（兼容秒/毫秒时间戳），样式走 chartTheme 统一类目轴
 * @param {Array} list 时间序列
 * @returns {Object} ECharts xAxis 选项
 */
function baseXAxis(list) {
  return chartCategoryAxis(list.map((i) => formatClock(i.t)))
}

/**
 * 渲染 RT 趋势曲线（avg/p90/p95/p99 四线，低饱和主题色）
 */
function renderRtChart() {
  rtChart?.setOption(
    {
      tooltip: chartTooltip(' ms'),
      legend: chartLegend(['Avg', 'P90', 'P95', 'P99']),
      grid: CHART_GRID,
      xAxis: baseXAxis(series.value),
      yAxis: chartValueAxis('ms'),
      series: [
        chartLine('Avg', series.value.map((i) => (i.avgMs == null ? null : Number(i.avgMs))), CHART_COLORS.primary),
        chartLine('P90', series.value.map((i) => (i.p90 == null ? null : Number(i.p90))), CHART_COLORS.teal),
        chartLine('P95', series.value.map((i) => (i.p95 == null ? null : Number(i.p95))), CHART_COLORS.amber),
        chartLine('P99', series.value.map((i) => (i.p99 == null ? null : Number(i.p99))), CHART_COLORS.rose)
      ]
    },
    true
  )
}

/**
 * 渲染 TPS 图：仅 TPS 折线（错误趋势由下方错误时间分布图承载，避免混轴干扰读数）
 */
function renderTpsChart() {
  tpsChart?.setOption(
    {
      tooltip: chartTooltip(),
      legend: chartLegend(['TPS']),
      grid: CHART_GRID,
      xAxis: baseXAxis(series.value),
      yAxis: [{ ...chartValueAxis('TPS'), minInterval: 1 }],
      series: [
        chartLine('TPS', series.value.map((i) => Number(i.tps) || 0), CHART_COLORS.primary)
      ]
    },
    true
  )
}

/**
 * 渲染错误时间分布小曲线（平滑线，玫红色）
 */
function renderErrChart() {
  const timeline = errors.value.timeline || []
  errChart?.setOption(
    {
      tooltip: { ...chartTooltip(), axisPointer: { type: 'shadow' } },
      grid: { left: 40, right: 12, top: 16, bottom: 24 },
      xAxis: baseXAxis(timeline),
      yAxis: [{ ...chartValueAxis('错误'), minInterval: 1 }],
      series: [
        { ...chartLine('错误数', timeline.map((i) => Number(i.errorCount) || 0), CHART_COLORS.rose), smooth: true }
      ]
    },
    true
  )
}

/**
 * 渲染压力机资源双图（CPU%/MEM%，每节点一条线）：
 * 各节点心跳采样按序号对齐（采样同为 10 秒粒度，误差可忽略），x 轴取采样最多节点的时间列
 * @param {string} metric 资源字段：cpu / mem
 * @param {object|null} chart 图表实例（未初始化时跳过）
 */
function renderNodeResChart(metric, chart) {
  const rows = nodes.value.filter((row) => (row.resources || []).length)
  if (!chart || !rows.length) {
    return
  }
  // x 轴时间取采样点最多的节点（各节点点数基本一致）
  const longest = rows.reduce((a, b) => ((b.resources.length > a.resources.length) ? b : a))
  const palette = NODE_COLORS
  chart.setOption(
    {
      tooltip: chartTooltip(),
      legend: chartLegend(rows.map((row) => nodeLabel(row))),
      grid: CHART_GRID,
      xAxis: baseXAxis(longest.resources),
      yAxis: [{ ...chartValueAxis(metric === 'cpu' ? 'CPU%' : 'MEM%'), max: 100 }],
      series: rows.map((row, i) => chartLine(
        nodeLabel(row),
        row.resources.map((point) => Number(point[metric]) || 0),
        palette[i % palette.length]
      ))
    },
    true
  )
}

/**
 * 渲染压力机 CPU/内存两张资源曲线
 */
function renderNodeResCharts() {
  renderNodeResChart('cpu', nodeCpuChart)
  renderNodeResChart('mem', nodeMemChart)
  renderNodeNetChart()
}

/**
 * 渲染压力机网络带宽图（Mbps）：每节点一条线 = 收+发合计，
 * 收发分解保留在 tooltip 明细中（hover 查看），主线保持简洁
 */
function renderNodeNetChart() {
  const rows = nodes.value.filter((row) => (row.resources || []).length)
  if (!nodeNetChart || !rows.length) {
    return
  }
  const longest = rows.reduce((a, b) => ((b.resources.length > a.resources.length) ? b : a))
  const palette = NODE_COLORS
  const series = rows.map((row, i) => chartLine(
    nodeLabel(row),
    row.resources.map((p) => bpsToMbps((Number(p.recvBps) || 0) + (Number(p.sentBps) || 0))),
    palette[i % palette.length]
  ))
  nodeNetChart.setOption(
    {
      tooltip: {
        ...chartTooltip(),
        formatter: (params) => {
          const idx = Array.isArray(params) ? params[0].dataIndex : params.dataIndex
          const lines = [params[0].axisValueLabel]
          rows.forEach((row) => {
            const point = row.resources[Math.min(idx, row.resources.length - 1)] || {}
            const recv = bpsToMbps(point.recvBps)
            const sent = bpsToMbps(point.sentBps)
            lines.push(`${nodeLabel(row)}：合计 ${bpsToMbps((Number(point.recvBps) || 0) + (Number(point.sentBps) || 0))} Mbps（收 ${recv} / 发 ${sent}）`)
          })
          return lines.join('<br/>')
        }
      },
      legend: chartLegend(rows.map((row) => nodeLabel(row))),
      grid: CHART_GRID,
      xAxis: baseXAxis(longest.resources),
      yAxis: [{ ...chartValueAxis('Mbps') }],
      series
    },
    true
  )
}

/**
 * 字节/秒转 Mbps（保留两位小数）
 * @param {number|string} bps 字节每秒
 * @returns {number} Mbps
 */
function bpsToMbps(bps) {
  return Math.round((((Number(bps) || 0) * 8) / 1e6) * 100) / 100
}

/**
 * 节点展示名：主机名优先，缺失回退 nodeKey 缩略
 * @param {Object} row 节点行
 * @returns {string} 展示名
 */
function nodeLabel(row) {
  return row.hostname || shortNodeKey(row.nodeKey)
}

/** 压力机资源三图的节点配色（取主题真实存在的 6 色，超出轮转） */
const NODE_COLORS = [
  CHART_COLORS.primary,
  CHART_COLORS.teal,
  CHART_COLORS.amber,
  CHART_COLORS.rose,
  CHART_COLORS.violet,
  CHART_COLORS.slate
]

/**
 * 初始化图表实例（容器随报告模板渲染后才存在，需懒初始化）
 */
function ensureCharts() {
  if (rtChartRef.value && !rtChart) {
    rtChart = echarts.init(rtChartRef.value)
  }
  if (tpsChartRef.value && !tpsChart) {
    tpsChart = echarts.init(tpsChartRef.value)
  }
  if (errChartRef.value && !errChart) {
    errChart = echarts.init(errChartRef.value)
  }
  if (nodeCpuChartRef.value && !nodeCpuChart) {
    nodeCpuChart = echarts.init(nodeCpuChartRef.value)
  }
  if (nodeMemChartRef.value && !nodeMemChart) {
    nodeMemChart = echarts.init(nodeMemChartRef.value)
  }
  if (nodeNetChartRef.value && !nodeNetChart) {
    nodeNetChart = echarts.init(nodeNetChartRef.value)
  }
}

/**
 * 加载报告数据并渲染全部区块图表
 */
async function load() {
  loading.value = true
  try {
    const res = await getTaskReport(route.params.id)
    report.value = res.data || null
    await nextTick()
    ensureCharts()
    renderRtChart()
    renderTpsChart()
    renderErrChart()
    renderNodeResCharts()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 导出：调用导出接口下载 HTML 报告文件（文件名 report-{taskNo}.html）；
 * 后端异常时可能返回 JSON 错误 blob（blob.type 含 json），此时解析出后端 message 以错误提示展示
 */
async function handleExport() {
  if (exporting.value) {
    return
  }
  exporting.value = true
  try {
    const blob = await exportReport(route.params.id)
    // blob 响应无 {code,message,data} 结构，会被拦截器原样透传；错误时后端返回 JSON blob
    if (blob && blob.type && blob.type.includes('json')) {
      const text = await blob.text()
      let message = '报告导出失败'
      try {
        message = JSON.parse(text)?.message || message
      } catch {
        // 非 JSON 文本则保留默认提示
      }
      ElMessage.error(message)
      return
    }
    const taskNo = summary.value.taskNo || route.params.id
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `report-${taskNo}.html`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('报告已导出')
  } catch {
    // 网络层错误提示已由 http.js 拦截器统一弹出
  } finally {
    exporting.value = false
  }
}

/**
 * 打印：调起浏览器打印（可选择另存为 PDF）
 */
/**
 * 调起浏览器打印（配合下方非 scoped 的 @media print 样式：
 * 只打印报告主体，隐藏平台侧栏/顶栏/页面操作按钮，并还原背景色）
 */
function handlePrint() {
  window.print()
}

/**
 * 窗口尺寸变化：三张图表自适应
 */
function handleResize() {
  rtChart?.resize()
  tpsChart?.resize()
  errChart?.resize()
  nodeCpuChart?.resize()
  nodeMemChart?.resize()
  nodeNetChart?.resize()
}

// 页面挂载：注册 resize 监听并加载数据（图表初始化由 load 内懒执行）
onMounted(() => {
  window.addEventListener('resize', handleResize)
  load()
})

// 页面卸载：移除监听并销毁图表实例
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  rtChart?.dispose()
  tpsChart?.dispose()
  errChart?.dispose()
  nodeCpuChart?.dispose()
  nodeMemChart?.dispose()
  nodeNetChart?.dispose()
  rtChart = null
  tpsChart = null
  errChart = null
  nodeCpuChart = null
  nodeMemChart = null
  nodeNetChart = null
})
</script>

<style>
/**
 * 打印样式（非 scoped，仅在打印媒体生效）：
 * 1) 隐藏平台侧栏、顶栏与本页操作区（返回/打印/导出按钮），只打印报告主体
 * 2) print-color-adjust: exact 还原背景色与图表颜色（浏览器默认会去背景色）
 * 3) 展开主内容区滚动容器，避免打印内容被裁切
 */
@media print {
  /* 隐藏平台框架与本页操作区（打印/导出按钮所在的页头） */
  .layout-aside,
  .layout-header,
  .page-header {
    display: none !important;
  }
  /* 报告主体占满纸张并去掉页边留白 */
  .layout-main {
    padding: 0 !important;
    overflow: visible !important;
  }
  .page,
  .report-wrap {
    margin: 0 !important;
    padding: 0 !important;
    width: 100% !important;
    max-width: 100% !important;
  }
  /* 卡片去阴影，适配纸面 */
  .block-card {
    box-shadow: none !important;
    border: 1px solid #dcdfe6 !important;
    break-inside: avoid;
  }
  /* 统一打印字号：正文与表格 12px，区块标题 14px 加粗，消除屏幕端混排字号差异 */
  .report-wrap,
  .report-wrap .el-table,
  .report-wrap .el-table .el-table__cell,
  .report-wrap .el-descriptions__body,
  .report-wrap .el-descriptions__label,
  .report-wrap .el-descriptions__content,
  .report-wrap .el-tag {
    font-size: 12px !important;
    line-height: 1.6 !important;
  }
  .report-wrap .card-title,
  .report-wrap .el-descriptions__title,
  .report-wrap h3 {
    font-size: 14px !important;
    font-weight: 600 !important;
  }
  /* KPI 数值保留视觉层次但统一尺寸（纯数值+单位在标题，打印不换行） */
  .report-wrap .kpi-value {
    font-size: 15px !important;
    font-weight: 700 !important;
  }
  /* 背景色/图表颜色原样打印 */
  * {
    -webkit-print-color-adjust: exact !important;
    print-color-adjust: exact !important;
  }
}
</style>

<style scoped>
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--pp-text-primary);
}

.report-wrap {
  margin-top: 16px;
  min-height: 300px;
}

.block-card {
  margin-bottom: 12px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--pp-text-primary);
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--pp-text-regular);
  margin: 6px 0 8px;
}

/* KPI 卡等宽均分（7 张卡，栅格 24 列除不尽，改用 flex） */
.kpi-col {
  flex: 1 1 0;
  min-width: 0;
}

.kpi-box {
  text-align: center;
  padding: 10px 4px;
  border: 1px solid var(--pp-border);
  border-radius: 6px;
}

.kpi-label {
  font-size: 12px;
  color: var(--pp-text-secondary);
}

.kpi-value {
  margin-top: 6px;
  /* KPI 数值字号与整页层级协调（纯数值后更短，15px 加粗即可突出且不换行） */
  font-size: 15px;
  font-weight: 700;
  line-height: 1.2;
  word-break: break-all;
}

.chart-box {
  width: 100%;
  height: 300px;
  margin-top: 12px;
}

.chart-box-small {
  width: 100%;
  height: 260px;
}

.mono {
  font-family: var(--pp-font-mono);
  font-size: 13px;
}

/* 节点单元格：主机名主行 + IP 副行 */
.node-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.node-name {
  font-weight: 500;
}

.node-ip {
  font-size: 12px;
  color: var(--pp-text-secondary);
}

.error-text {
  display: block;
  color: var(--pp-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 长尾表：p99 > 1000ms 的行整体标红 */
:deep(tr.slow-row) {
  color: var(--pp-danger);
}

:deep(tr.slow-row td .cell) {
  color: var(--pp-danger);
}
</style>
