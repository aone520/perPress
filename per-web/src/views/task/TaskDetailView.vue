<!--
  任务详情页：
  - 顶部返回按钮 + 任务信息卡（任务号/名称/模式/脚本版本/状态/创建人/起止时间/压力配置结构化明细——按模式展示全部参数与占比/堆内存/定时时间）
  - 任务操作：启动（仅 CREATED）、停止（RUNNING/PREPARING），均二次确认
  - 节点执行明细表：节点（nodeKey 前 8 位）/节点状态/本节点并发/分片序号/起止时间/错误信息
  - 任务处于非终态时每 5 秒轮询刷新详情（页面隐藏时跳过，卸载时清除定时器）
-->
<template>
  <div class="page">
    <!-- 顶部返回 -->
    <el-page-header @back="goBack">
      <template #content>
        <span class="page-title">任务详情</span>
      </template>
    </el-page-header>

    <div v-loading="loading" class="detail-wrap">
      <template v-if="task">
        <!-- 任务信息卡 -->
        <div class="page-card">
          <div class="card-header">
            <div class="card-title">任务信息</div>
            <div>
              <el-button
                v-if="task.status === 'RUNNING'"
                type="warning"
                :icon="DataLine"
                @click="goMonitor"
              >
                监控大屏
              </el-button>
              <el-button
                v-if="task.status === 'FINISHED'"
                type="primary"
                :icon="TrendCharts"
                @click="goReport"
              >
                查看报告
              </el-button>
              <el-button
                v-if="task.status === 'CREATED' && task.triggerType !== 'SCHEDULED'"
                type="success"
                :loading="actionLoading"
                :icon="VideoPlay"
                @click="handleStart"
              >
                启动任务
              </el-button>
              <!-- 定时任务（CREATED 且 SCHEDULED）：平台按定时时间自动触发，此处可覆盖定时立即执行 -->
              <el-button
                v-if="task.status === 'CREATED' && task.triggerType === 'SCHEDULED'"
                type="warning"
                :loading="actionLoading"
                :icon="VideoPlay"
                @click="handleStart"
              >
                改为立即执行
              </el-button>
              <el-button
                v-if="['RUNNING', 'PREPARING'].includes(task.status)"
                type="danger"
                :loading="actionLoading"
                :icon="VideoPause"
                @click="handleStop"
              >
                停止任务
              </el-button>
            </div>
          </div>
          <!-- ① 基本信息（对齐创建向导步骤1） -->
          <div class="section-title">基本信息</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="任务号">
              <span class="mono">{{ task.taskNo || task.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="任务名称">{{ task.name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="TASK_STATUS_META[task.status]?.type || 'info'">
                {{ TASK_STATUS_META[task.status]?.text || task.status }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="压测脚本">
              <!-- 脚本名可点击跳转脚本详情（脚本已被删除时降级为纯文本） -->
              <el-link
                v-if="task.scriptName && task.scriptId"
                type="primary"
                :underline="false"
                @click="router.push(`/scripts/${task.scriptId}`)"
              >
                {{ task.scriptName }} v{{ task.scriptVersion }}
              </el-link>
              <span v-else>{{ task.scriptName ? `${task.scriptName} v${task.scriptVersion}` : '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="执行方式">
              <el-tag
                :type="task.triggerType === 'SCHEDULED' ? 'warning' : 'info'"
                effect="plain"
              >
                {{ task.triggerType === 'SCHEDULED' ? '定时' : '立即执行' }}
              </el-tag>
              <span v-if="task.triggerType === 'SCHEDULED' && task.scheduledStartTime" class="schedule-time">
                {{ task.scheduledStartTime }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="创建人">{{ task.createBy || '-' }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">
              {{ formatDateTime(task.startTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="结束时间">
              {{ formatDateTime(task.endTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(task.createTime) }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- ② 压力配置（对齐创建向导步骤2：模式 + 模式参数 + 占比 + 堆内存） -->
          <div class="section-title">压力配置</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="压测模式">
              <el-tag v-if="task.mode === 'CONCURRENT'" effect="plain">并发模式</el-tag>
              <el-tag v-else-if="task.mode === 'FIXED_TPS'" effect="plain">固定TPS</el-tag>
              <el-tag v-else-if="task.mode === 'STEPPED'" effect="plain">阶梯压测</el-tag>
              <el-tag v-else effect="plain">{{ task.mode || '-' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item
              v-for="item in modeConfigRows"
              :key="item.label"
              :label="item.label"
              :span="item.span || 1"
            >
              {{ item.value }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- ③ 流量占比（多执行单元任务的占比分配，独立分块对齐创建向导的占比配置区） -->
          <template v-if="weightRows.length">
            <div class="section-title">流量占比</div>
            <el-table :data="weightRows" size="small" class="dispatch-table">
              <el-table-column prop="index" label="#" width="50" align="center" />
              <el-table-column prop="name" label="执行单元" min-width="220" show-overflow-tooltip />
              <el-table-column prop="type" label="类型" width="110" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.type === '并行接口' ? 'danger' : 'info'" effect="plain">
                    {{ row.type }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="占比" width="180" align="center">
                <template #default="{ row }">
                  <span class="weight-num">{{ row.weight }}%</span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-- ③+ 接口流量漏斗（FIXED_TPS 任务配置了接口占比时展示，压测后可对照实际比例） -->
          <template v-if="funnelRows.length">
            <div class="section-title">接口流量漏斗</div>
            <el-table :data="funnelRows" size="small" class="dispatch-table">
              <el-table-column prop="group" label="串行组" min-width="140" show-overflow-tooltip />
              <el-table-column prop="name" label="接口" min-width="180" show-overflow-tooltip />
              <el-table-column label="任务占比" width="110" align="center">
                <template #default="{ row }">
                  <span class="weight-num">{{ row.percent }}%</span>
                </template>
              </el-table-column>
              <el-table-column label="脚本默认" width="170" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.stale" size="small" type="warning" effect="plain">脚本中已不存在</el-tag>
                  <span v-else-if="row.overridden">{{ row.defaultPercent }}%（已覆盖）</span>
                  <span v-else>同脚本 {{ row.defaultPercent }}%</span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-- ④ 参测节点（对齐创建向导步骤2的节点选择） -->
          <div class="section-title">参测节点（{{ participantNodes.length }}）</div>
          <template v-if="participantNodes.length">
            <el-tag
              v-for="(node, i) in participantNodes"
              :key="i"
              class="node-tag"
              effect="plain"
            >
              {{ node }}
            </el-tag>
          </template>
          <span v-else class="muted">-</span>

          <!-- ⑤ 参数文件分发（对齐创建向导步骤3） -->
          <div class="section-title">参数文件分发</div>
          <el-table
            v-if="fileDispatchRows.length"
            :data="fileDispatchRows"
            size="small"
            class="dispatch-table"
          >
            <el-table-column prop="fileName" label="参数文件" min-width="200" show-overflow-tooltip />
            <el-table-column label="分发模式" width="220">
              <template #default="{ row }">
                <el-tag :type="row.mode === 'SPLIT' ? 'warning' : 'info'" effect="plain">
                  {{ row.mode === 'SPLIT' ? '拆分（SPLIT）' : '公用（SHARED）' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="220">
              <template #default="{ row }">
                {{ row.mode === 'SPLIT' ? '按行均分到各节点，保证参数唯一' : '各节点使用同一份完整文件' }}
              </template>
            </el-table-column>
          </el-table>
          <span v-else class="muted">未关联参数文件，无需分发</span>
        </div>

        <!-- 节点执行明细 -->
        <div class="page-card">
          <div class="card-title">节点执行明细（{{ nodes.length }}）</div>
          <el-table :data="nodes">
            <el-table-column label="节点" min-width="170">
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
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag
                  :type="nodeStatusType(row.status)"
                  :class="{ 'tag-cyan': row.status === 'READY' }"
                >
                  {{ NODE_STATUS_META[row.status]?.text || row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="本节点并发" width="110" align="center">
              <template #default="{ row }">{{ row.jmeterProps?.threads ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="分片序号" width="100" align="center">
              <template #default="{ row }">
                {{ row.shardIndex === null || row.shardIndex === undefined ? '-' : row.shardIndex }}
              </template>
            </el-table-column>
            <el-table-column label="开始时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="170" align="center">
              <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
            </el-table-column>
            <el-table-column label="错误信息" min-width="180">
              <template #default="{ row }">
                <el-tooltip
                  v-if="row.errorMsg"
                  :content="row.errorMsg"
                  placement="top"
                >
                  <span class="error-text">{{ row.errorMsg }}</span>
                </el-tooltip>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
      <el-empty v-else-if="!loading" description="任务不存在或已被删除" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataLine, TrendCharts, VideoPlay, VideoPause } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { detail as taskDetail, startTask, stopTask } from '@/api/task'
import { detail as scriptDetail } from '@/api/script'
import { page as pageFiles } from '@/api/file'
import { formatDateTime } from '@/utils/format'

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

/** 节点执行状态元信息：展示文案与 tag 颜色（READY 青色通过自定义样式实现） */
const NODE_STATUS_META = {
  PENDING: { text: '待下发', type: 'info' },
  DOWNLOADING: { text: '下载中', type: 'primary' },
  READY: { text: '就绪', type: 'primary' },
  RUNNING: { text: '运行中', type: 'success' },
  STOPPED: { text: '已停止', type: 'info' },
  FINISHED: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
  EXCLUDED: { text: '已排除', type: 'info' }
}

/** 任务终态集合：到达终态后停止轮询 */
const TERMINAL_STATUSES = ['FINISHED', 'FAILED']

/** 轮询间隔（毫秒） */
const POLL_INTERVAL = 5000

/** 任务详情数据 / 节点明细 / 加载与操作状态 */
const task = ref(null)
const nodes = ref([])
const loading = ref(false)
const actionLoading = ref(false)

/**
 * 压力配置结构化展示行：按压测模式渲染模式专属参数，
 * 追加通用项（流量占比 / 堆内存 / 定时时间），供基本信息卡 el-descriptions 逐行展示
 */
const modeConfigRows = computed(() => {
  const c = task.value?.config || {}
  const mode = task.value?.mode
  const rows = []
  if (mode === 'FIXED_TPS') {
    rows.push(
      { label: '目标TPS', value: c.tps ?? '-' },
      { label: '启动时长', value: `${c.rampupSeconds ?? 10} 秒` },
      { label: '持续时长', value: `${c.durationSeconds ?? '-'} 秒` },
      {
        label: '线程上限',
        value: c.maxThreads ?? '自动 min(tps×2, 2000)'
      }
    )
  } else if (mode === 'STEPPED') {
    const unitName = c.unit === 'TPS' ? 'TPS' : '并发'
    rows.push(
      { label: '阶梯单位', value: c.unit === 'TPS' ? '按吞吐（TPS）' : '按并发（THREADS）' },
      { label: '起始值', value: `${c.start ?? '-'} ${unitName}` },
      { label: '步长', value: `${c.step ?? '-'} ${unitName}` },
      { label: '每步持续', value: `${c.stepSeconds ?? '-'} 秒` },
      { label: '启动时长', value: `${c.rampupSeconds ?? 30} 秒` },
      { label: '峰值', value: `${c.peak ?? '-'} ${unitName}` },
      { label: '封顶持续', value: `${c.peakSeconds ?? '-'} 秒` }
    )
  } else {
    rows.push(
      { label: '总并发', value: c.threads ?? '-' },
      { label: '启动时长', value: `${c.rampupSeconds ?? '-'} 秒` },
      { label: '持续时长', value: `${c.durationSeconds ?? '-'} 秒` }
    )
  }
  // JMeter 堆内存（任务级覆盖）
  rows.push({ label: 'JMeter堆内存', value: c.jmeterHeapMb ? `${c.jmeterHeapMb} MB` : '节点默认 2048 MB' })
  return rows
})

/** 脚本详情数据（formDef/versions/type，用于展开执行单元名） */
const scriptData = ref(null)

/**
 * 执行单元列表（与后端 expandUnits 镜像）：SERIAL 组整体 1 个单元、PARALLEL 组内每接口 1 个单元；
 * 导入脚本为 JMX 线程组列表。weights 数组顺序与该列表一一对应
 */
const execUnits = computed(() => {
  if (!scriptData.value) {
    return []
  }
  if (scriptData.value.type === 'IMPORTED') {
    const ver = (scriptData.value.versions || []).find((v) => Number(v.version) === Number(task.value?.scriptVersion))
    return (ver?.threadGroups || []).map((name) => ({ name, type: '线程组' }))
  }
  const def = scriptData.value.formDef
  if (!def) {
    return []
  }
  const groups = (def.groups && def.groups.length)
    ? def.groups
    : [{ name: def.threadGroupName || '接口组', execution: 'SERIAL', samplers: def.samplers || [] }]
  const units = []
  groups.forEach((g) => {
    if (g.execution === 'PARALLEL') {
      (g.samplers || []).forEach((s) => {
        units.push({ name: `${g.name || '接口组'}-${s.name || s.url}`, type: '并行接口' })
      })
    } else {
      units.push({ name: g.name || `分组${units.length + 1}`, type: '串行组' })
    }
  })
  return units
})

/**
 * 流量占比展示行：weights 与执行单元顺序对齐（单元缺失时按序号占位），
 * 并按占比降序展示分配百分比
 */
const weightRows = computed(() => {
  const weights = task.value?.config?.weights
  if (!Array.isArray(weights) || weights.length < 2) {
    return []
  }
  return weights.map((w, i) => ({
    index: i + 1,
    name: execUnits.value[i]?.name || `执行单元 ${i + 1}`,
    type: execUnits.value[i]?.type || '-',
    weight: w
  }))
})

/**
 * 接口流量漏斗展示行：任务 config.funnelPercents（key=组名/接口名）对照脚本默认值，
 * 标注是否覆盖；脚本已改动导致 key 失配时标记"脚本中已不存在"（提示任务快照基于旧脚本结构）
 */
const funnelRows = computed(() => {
  const funnel = task.value?.config?.funnelPercents
  if (task.value?.mode !== 'FIXED_TPS' || !funnel) {
    return []
  }
  // 脚本串行组接口默认占比映射（key 规则与后端 overrideFunnelPercents 一致）
  const defaults = {}
  const def = scriptData.value?.formDef
  if (def) {
    const groups = (def.groups && def.groups.length)
      ? def.groups
      : [{ name: def.threadGroupName || '接口组', execution: 'SERIAL', samplers: def.samplers || [] }]
    groups.forEach((g) => {
      if (g.execution === 'PARALLEL') {
        return
      }
      const groupLabel = (g.name || '').trim() || '接口组'
      ;(g.samplers || []).forEach((s) => {
        const samplerName = (s.name || '').trim() || s.url || ''
        defaults[`${groupLabel}/${samplerName}`] = s.trafficPercent ?? 100
      })
    })
  }
  return Object.entries(funnel).map(([key, percent]) => {
    const idx = key.indexOf('/')
    const hasDefault = key in defaults
    return {
      key,
      group: idx > 0 ? key.slice(0, idx) : '-',
      name: idx > 0 ? key.slice(idx + 1) : key,
      percent,
      defaultPercent: hasDefault ? defaults[key] : null,
      overridden: hasDefault && Number(defaults[key]) !== Number(percent),
      stale: !hasDefault
    }
  })
})

/** 参测节点展示列表：从节点执行明细去重提取「主机名(IP)」，节点已删除时回退 nodeKey 缩略 */
const participantNodes = computed(() => {
  const seen = new Set()
  const list = []
  for (const node of nodes.value || []) {
    const display = node.hostname
      ? `${node.hostname}${node.ip ? `（${node.ip}）` : ''}`
      : shortNodeKey(node.nodeKey)
    if (!seen.has(display)) {
      seen.add(display)
      list.push(display)
    }
  }
  return list
})

/** 参数文件分发展示行：fileDispatch 的 fileId 映射文件库名称 */
const fileDispatchRows = computed(() => {
  const dispatch = task.value?.fileDispatch
  if (!Array.isArray(dispatch) || !dispatch.length) {
    return []
  }
  return dispatch.map((item) => {
    const file = fileOptions.value.find((f) => Number(f.id) === Number(item.fileId))
    return {
      fileId: item.fileId,
      fileName: file ? file.name : `文件 #${item.fileId}`,
      mode: item.mode || 'SHARED'
    }
  })
})

/** 文件库选项（fileId → 文件名映射用） */
const fileOptions = ref([])

/** 轮询定时器句柄 */
let pollTimer = null

/**
 * 获取节点执行状态的 tag 类型（READY 使用青色自定义样式，此处返回 primary 兜底）
 * @param {string} status 节点执行状态
 * @returns {string} el-tag 的 type
 */
function nodeStatusType(status) {
  return NODE_STATUS_META[status]?.type || 'info'
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
 * 加载任务详情：silent 为 true 时静默刷新（轮询场景不展示 loading）
 * @param {boolean} silent 是否静默刷新
 */
async function loadDetail(silent = false) {
  if (!silent) {
    loading.value = true
  }
  try {
    const res = await taskDetail(route.params.id)
    task.value = res.data || null
    nodes.value = task.value?.nodes || []
    // 首次加载时同步拉取文件库选项（参数文件分发名称映射用）与脚本详情（执行单元展开用）
    if (!fileOptions.value.length) {
      try {
        const fileRes = await pageFiles({ page: 1, size: 100 })
        fileOptions.value = fileRes.data?.records || []
      } catch {
        // 文件列表拉取失败时以「文件 #id」占位展示
      }
    }
    if (task.value?.scriptId && !scriptData.value) {
      try {
        const scriptRes = await scriptDetail(task.value.scriptId)
        scriptData.value = scriptRes.data || null
      } catch {
        // 脚本已删除时执行单元名回退序号占位
      }
    }
    if (task.value && TERMINAL_STATUSES.includes(task.value.status)) {
      stopPolling()
    }
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/**
 * 启动轮询：任务非终态时每 5 秒刷新一次详情（页面隐藏时跳过本次请求）
 */
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (document.visibilityState === 'hidden') {
      return
    }
    await loadDetail(true)
  }, POLL_INTERVAL)
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
 * 返回任务列表页
 */
function goBack() {
  router.push('/tasks')
}

/**
 * 跳转监控大屏（携带当前任务 ID）
 */
function goMonitor() {
  router.push({ path: '/monitor', query: { taskId: String(route.params.id) } })
}

/**
 * 跳转当前任务的压测报告页
 */
function goReport() {
  router.push(`/reports/${route.params.id}`)
}

/**
 * 启动任务：二次确认后调用启动接口，成功后立即刷新详情并恢复轮询
 */
async function handleStart() {
  try {
    await ElMessageBox.confirm(
      `确认启动任务「${task.value?.name}」开始压测吗？`,
      '启动确认',
      { type: 'warning', confirmButtonText: '启动', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await startTask(route.params.id)
    ElMessage.success('任务已启动')
    await loadDetail()
    if (task.value && !TERMINAL_STATUSES.includes(task.value.status)) {
      startPolling()
    }
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    actionLoading.value = false
  }
}

/**
 * 停止任务：二次确认后调用停止接口，成功后立即刷新详情
 */
async function handleStop() {
  try {
    await ElMessageBox.confirm(
      `确认停止任务「${task.value?.name}」吗？已运行的压测将被终止。`,
      '停止确认',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = true
  try {
    await stopTask(route.params.id)
    ElMessage.success('停止指令已下发')
    await loadDetail()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    actionLoading.value = false
  }
}

// 页面挂载后加载详情；任务非终态时开启轮询
onMounted(async () => {
  await loadDetail()
  if (task.value && !TERMINAL_STATUSES.includes(task.value.status)) {
    startPolling()
  }
})

// 页面卸载时清除轮询定时器，避免内存泄漏
onUnmounted(stopPolling)
</script>

<style scoped>
.page-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--pp-text-primary);
}

.detail-wrap {
  margin-top: 16px;
  min-height: 300px;
}

.info-card {
  margin-bottom: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--pp-text-primary);
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

/* READY 状态的青色 tag：覆盖 Element Plus 主题变量 */
.tag-cyan {
  --el-tag-bg-color: #13c2c2;
  --el-tag-border-color: #13c2c2;
  --el-tag-text-color: #ffffff;
}

.error-text {
  display: block;
  color: var(--pp-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 信息卡分块标题（对齐创建向导的填写块） */
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--pp-text-primary);
  margin: 18px 0 10px;
}

.section-title:first-of-type {
  margin-top: 0;
}

/* 参测节点标签 */
.node-tag {
  margin: 0 8px 8px 0;
}

/* 参数文件分发表 */
.dispatch-table {
  width: 100%;
}

/* 弱化占位文案 */
.muted {
  color: var(--pp-text-secondary);
  font-size: 13px;
}

/* 占比数值强调 */
.weight-num {
  font-weight: 600;
  color: var(--pp-primary);
}
</style>
