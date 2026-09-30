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
    <el-page-header @back="goBack">
      <template #content>
        <span class="page-title">压测报告</span>
      </template>
      <template #extra>
        <el-button :icon="Printer" @click="handlePrint">打印</el-button>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="handleExport">
          导出
        </el-button>
      </template>
    </el-page-header>

    <div v-loading="loading" class="report-wrap">
      <template v-if="report">
        <!-- ① 报告头 -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">报告头</span></template>
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
        </el-card>

        <!-- ② 全局 KPI -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">全局 KPI</span></template>
          <el-row :gutter="12">
            <el-col v-for="card in kpiCards" :key="card.label" class="kpi-col">
              <div class="kpi-box">
                <div class="kpi-label">{{ card.label }}</div>
                <div class="kpi-value" :style="{ color: card.color }">{{ card.value }}</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- ③ 响应时间分位数 + RT 趋势 -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">响应时间（ms）</span></template>
          <el-table :data="[rtRow]" border size="small">
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
        </el-card>

        <!-- ④ TPS 趋势 -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">TPS 趋势</span></template>
          <div ref="tpsChartRef" class="chart-box" />
        </el-card>

        <!-- ⑤ 事务明细 -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">事务明细（{{ samplers.length }}）</span></template>
          <el-table :data="samplers" border stripe size="small">
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
        </el-card>

        <!-- ⑥ 节点明细 -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">节点明细（{{ nodes.length }}）</span></template>
          <el-table :data="nodes" border stripe size="small">
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
          </el-table>
        </el-card>

        <!-- ⑦ 错误分析 -->
        <el-card shadow="never" class="block-card">
          <template #header><span class="card-title">错误分析</span></template>
          <el-row :gutter="12">
            <el-col :span="8">
              <div class="sub-title">错误码分布</div>
              <el-table :data="errors.byCode" border size="small" max-height="260">
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
              <el-table :data="errors.topSamplers" border size="small" max-height="260">
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
          <el-table :data="errors.samples" border stripe size="small" max-height="320">
            <el-table-column label="时间" width="180" align="center">
              <template #default="{ row }">
                {{ formatDateTime(row.createTime ?? row.ts) }}
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
        </el-card>

        <!-- ⑧ 长尾与慢事务 -->
        <el-card shadow="never" class="block-card">
          <template #header>
            <span class="card-title">长尾与慢事务（按 P99 降序，&gt;1000ms 标红）</span>
          </template>
          <el-table :data="slowSamplers" border size="small" :row-class-name="slowRowClass">
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
        </el-card>
      </template>

      <el-empty v-else-if="!loading" description="报告不存在或任务尚未生成报告" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Download, Printer } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { exportReport, getTaskReport } from '@/api/metric'
import { formatBytes, formatClock, formatDateTime } from '@/utils/format'

// echarts 按需注册：折线 / 柱状 + tooltip + legend + grid + canvas 渲染器
echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const route = useRoute()
const router = useRouter()

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
let rtChart = null
let tpsChart = null
let errChart = null

/** 报告摘要（无数据时给空对象兜底） */
const summary = computed(() => report.value?.summary || {})

/** 时间序列 / 事务明细 / 节点明细 */
const series = computed(() => report.value?.series || [])
const samplers = computed(() => report.value?.samplers || [])
const nodes = computed(() => report.value?.nodes || [])

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

/** 全局 KPI 8 张卡：单位统一放标题括号内，值只显示纯数值（避免打印换行） */
const kpiCards = computed(() => [
  { label: 'APDEX', value: summary.value.apdex ?? '-', color: '#9254de' },
  { label: '总请求', value: num(summary.value.totalCount), color: '#409eff' },
  { label: '总错误', value: num(summary.value.totalErrorCount), color: '#f56c6c' },
  { label: '错误率 (%)', value: fixed(summary.value.errorRate), color: '#f56c6c' },
  { label: '平均 TPS', value: num(summary.value.avgTps), color: '#409eff' },
  { label: '峰值 TPS', value: num(summary.value.peakTps), color: '#67c23a' },
  { label: '吞吐 (KB/s)', value: fixed(throughputKbps.value), color: '#e6a23c' },
  { label: '峰值线程', value: num(summary.value.peakThreads), color: '#909399' }
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
 * 构建基础 xAxis 配置：类目轴 + 时间格式化为 HH:mm:ss（兼容秒/毫秒时间戳）+ 重叠标签自动隐藏
 * @param {Array} list 时间序列
 * @returns {Object} ECharts xAxis 选项
 */
function baseXAxis(list) {
  return {
    type: 'category',
    data: list.map((i) => formatClock(i.t)),
    axisLabel: { hideOverlap: true }
  }
}

/**
 * 渲染 RT 趋势曲线（avg/p95/p99 三线）
 */
function renderRtChart() {
  rtChart?.setOption(
    {
      tooltip: { trigger: 'axis', valueFormatter: (v) => (v == null ? '-' : `${v} ms`) },
      legend: { top: 4, data: ['Avg', 'P90', 'P95', 'P99'] },
      grid: { left: 52, right: 24, top: 42, bottom: 28 },
      xAxis: baseXAxis(series.value),
      yAxis: { type: 'value', name: 'ms' },
      series: [
        { name: 'Avg', type: 'line', smooth: false, showSymbol: false, itemStyle: { color: '#409eff' }, data: series.value.map((i) => (i.avgMs == null ? null : Number(i.avgMs))) },
        { name: 'P90', type: 'line', smooth: false, showSymbol: false, itemStyle: { color: '#67c23a' }, data: series.value.map((i) => (i.p90 == null ? null : Number(i.p90))) },
        { name: 'P95', type: 'line', smooth: false, showSymbol: false, itemStyle: { color: '#e6a23c' }, data: series.value.map((i) => (i.p95 == null ? null : Number(i.p95))) },
        { name: 'P99', type: 'line', smooth: false, showSymbol: false, itemStyle: { color: '#f56c6c' }, data: series.value.map((i) => (i.p99 == null ? null : Number(i.p99))) }
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
      tooltip: { trigger: 'axis', axisPointer: { type: 'line' } },
      legend: { top: 4, data: ['TPS'] },
      grid: { left: 52, right: 24, top: 42, bottom: 28 },
      xAxis: baseXAxis(series.value),
      yAxis: [{ type: 'value', name: 'TPS', minInterval: 1 }],
      series: [
        { name: 'TPS', type: 'line', smooth: false, showSymbol: false, itemStyle: { color: '#409eff' }, data: series.value.map((i) => Number(i.tps) || 0) }
      ]
    },
    true
  )
}

/**
 * 渲染错误时间分布小曲线（柱状）
 */
function renderErrChart() {
  const timeline = errors.value.timeline || []
  errChart?.setOption(
    {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 40, right: 12, top: 16, bottom: 24 },
      xAxis: baseXAxis(timeline),
      yAxis: { type: 'value', name: '错误', minInterval: 1 },
      series: [
        { name: '错误数', type: 'line', smooth: true, showSymbol: false, itemStyle: { color: '#f56c6c' }, data: timeline.map((i) => Number(i.errorCount) || 0) }
      ]
    },
    true
  )
}

/**
 * 初始化三张图表实例（容器随报告模板渲染后才存在，需懒初始化）
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
}

/**
 * 返回报告列表页
 */
function goBack() {
  router.push('/reports')
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
  rtChart = null
  tpsChart = null
  errChart = null
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
  /* 隐藏平台框架与页面操作按钮 */
  .layout-aside,
  .layout-header,
  .el-page-header {
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
  .report-wrap .block-title,
  .report-wrap .el-descriptions__title,
  .report-wrap h3 {
    font-size: 14px !important;
    font-weight: 600 !important;
  }
  /* KPI 数值保留视觉层次但统一尺寸（纯数值+单位在标题，打印不换行） */
  .report-wrap .kpi-value,
  .report-wrap .metric-value {
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
  color: #303133;
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
  color: #303133;
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
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
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.kpi-label {
  font-size: 12px;
  color: #909399;
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
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
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
  color: #909399;
}

.error-text {
  display: block;
  color: #f56c6c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 长尾表：p99 > 1000ms 的行整体标红 */
:deep(tr.slow-row) {
  color: #f56c6c;
}

:deep(tr.slow-row td .cell) {
  color: #f56c6c;
}
</style>
