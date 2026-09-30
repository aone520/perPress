<!--
  任务独立编辑页（全屏路由页，替代原 1200px 弹窗，风格与脚本编辑页一致）：
  - 路由 /tasks/new（新建）/tasks/:id/edit（编辑，仅 CREATED 状态任务）
  - 顶部工具栏：返回 + 标题 + 提交按钮；内容区单列卡片流：
    ① 基本信息（名称/脚本/版本 + 执行方式/定时时间，编辑模式脚本与版本锁定）
    ② 压测模式与压力配置（并发 / 固定TPS / 阶梯 + JMeter 堆内存）
    ③ 流量占比（多执行单元时按单元分配，总和须 =100%）
    ④ 选择压测节点（含离线节点预警）
    ⑤ 参数文件分发（版本关联文件时展示）
  - 编辑模式通过任务详情接口回填；未保存离开二次确认
-->
<template>
  <div class="edit-page">
    <!-- 顶部工具栏 -->
    <div class="edit-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" text @click="goBack" />
        <span class="page-name">{{ isEdit ? '编辑任务' : '新建压测任务' }}</span>
        <el-tag v-if="isEdit && form.name" size="small" effect="plain" class="name-tag">{{ form.name }}</el-tag>
      </div>
      <el-button type="primary" :icon="Check" :loading="form.submitting" @click="handleSubmit">
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </div>

    <!-- 内容区（单列居中卡片流） -->
    <div v-loading="loading" class="edit-content">
      <!-- ① 基本信息 -->
      <section class="edit-card">
        <div class="card-head">
          <div>
            <div class="card-title">基本信息</div>
            <div class="card-desc">任务名称与压测脚本{{ isEdit ? '（编辑时脚本与版本不可修改）' : '' }}</div>
          </div>
        </div>
        <el-alert
          v-if="isEdit"
          type="info"
          :closable="false"
          show-icon
          title="编辑任务时脚本与版本不可修改"
          class="mode-alert"
        />
        <el-form label-width="90px" class="grid-form">
          <div class="form-row">
            <el-form-item label="任务名称" required>
              <el-input v-model="form.name" placeholder="请输入任务名称" maxlength="64" clearable />
            </el-form-item>
            <el-form-item label="压测脚本" required>
              <el-select
                v-model="form.scriptId"
                placeholder="请选择压测脚本"
                :loading="form.scriptLoading"
                :disabled="isEdit"
                filterable
                class="w-full"
                @change="handleScriptChange"
              >
                <el-option
                  v-for="item in scriptOptions"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="脚本版本" required>
              <el-select
                v-model="form.version"
                placeholder="请先选择脚本"
                :loading="form.versionLoading"
                :disabled="isEdit || !form.scriptId"
                class="w-full"
              >
                <el-option
                  v-for="item in scriptVersions"
                  :key="item.version"
                  :label="versionLabel(item)"
                  :value="item.version"
                />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="执行方式" required>
              <el-radio-group v-model="form.triggerType">
                <el-radio value="MANUAL">立即执行</el-radio>
                <el-radio value="SCHEDULED">定时执行</el-radio>
              </el-radio-group>
            </el-form-item>
            <!-- 定时执行：选择未来的定时启动时间（小于当前时间不可选） -->
            <el-form-item
              v-if="form.triggerType === 'SCHEDULED'"
              label="定时时间"
              required
              class="row-span-2"
            >
              <el-date-picker
                v-model="form.scheduledStartTime"
                type="datetime"
                format="YYYY-MM-DD HH:mm:ss"
                value-format="YYYY-MM-DD HH:mm:ss"
                placeholder="选择定时执行时间"
                :disabled-date="disabledPastDate"
                class="w-full"
              />
            </el-form-item>
          </div>
        </el-form>
      </section>

      <!-- ② 压测模式与压力配置 -->
      <section class="edit-card">
        <div class="card-head">
          <div>
            <div class="card-title">压测模式</div>
            <div class="card-desc">三种模式 + 执行机堆内存；总压力按节点数自动均分</div>
          </div>
        </div>
        <el-radio-group v-model="form.mode" @change="handleModeChange">
          <el-radio value="CONCURRENT">并发模式</el-radio>
          <el-radio value="FIXED_TPS" :disabled="isImportedScript">固定TPS</el-radio>
          <el-radio value="STEPPED" :disabled="isImportedScript">阶梯压测</el-radio>
        </el-radio-group>
        <el-alert
          v-if="isImportedScript"
          type="warning"
          :closable="false"
          show-icon
          title="导入脚本仅支持并发模式（含多个线程组时可按线程组设置流量占比），TPS/阶梯请使用表单脚本"
          class="mode-alert"
        />

        <div class="sub-title section-divider">压力配置</div>

        <!-- 并发模式 -->
        <el-form v-if="form.mode === 'CONCURRENT'" label-width="90px" class="pressure-form">
          <el-form-item label="总并发数">
            <el-input-number v-model="form.threads" :min="1" :step="10" />
            <span class="unit-text">线程（按节点均分）</span>
          </el-form-item>
          <el-form-item label="启动时长">
            <el-input-number v-model="form.rampupSeconds" :min="0" :step="10" />
            <span class="unit-text">秒（Ramp-up）</span>
          </el-form-item>
          <el-form-item label="持续时长">
            <el-input-number v-model="form.durationSeconds" :min="1" :step="30" />
            <span class="unit-text">秒（Duration）</span>
          </el-form-item>
        </el-form>

        <!-- 固定TPS模式 -->
        <el-form v-else-if="form.mode === 'FIXED_TPS'" label-width="90px" class="pressure-form">
          <el-form-item label="目标TPS">
            <el-input-number v-model="form.tps" :min="1" :step="50" />
            <span class="unit-text">样本数 / 秒</span>
          </el-form-item>
          <el-form-item label="启动时长">
            <el-input-number v-model="form.rampupSeconds" :min="0" :step="5" />
            <span class="unit-text">秒（吞吐爬坡到目标 TPS 的时长，0 表示立即满速）</span>
          </el-form-item>
          <el-form-item label="持续时长">
            <el-input-number v-model="form.durationSeconds" :min="1" :step="30" />
            <span class="unit-text">秒</span>
          </el-form-item>
          <el-form-item label="线程上限">
            <el-input-number v-model="form.maxThreads" :min="1" :step="100" />
            <span class="unit-text">可选，留空默认 min(tps*2, 2000)</span>
          </el-form-item>
        </el-form>

        <!-- 阶梯压测模式 -->
        <template v-else>
          <el-form label-width="90px" class="pressure-form">
            <el-form-item label="阶梯单位">
              <el-select v-model="form.unit" class="w-160">
                <el-option label="按并发（THREADS）" value="THREADS" />
                <el-option label="按吞吐（TPS）" value="TPS" />
              </el-select>
            </el-form-item>
            <el-form-item label="起始值">
              <el-input-number v-model="form.start" :min="1" :step="5" />
              <span class="unit-text">{{ steppedUnitName }}</span>
            </el-form-item>
            <el-form-item label="步长">
              <el-input-number v-model="form.step" :min="1" :step="5" />
              <span class="unit-text">{{ steppedUnitName }} / 每步增量</span>
            </el-form-item>
            <el-form-item label="每步持续">
              <el-input-number v-model="form.stepSeconds" :min="1" :step="30" />
              <span class="unit-text">秒</span>
            </el-form-item>
            <el-form-item label="启动时长">
              <el-input-number v-model="form.rampupSeconds" :min="0" :step="5" />
              <span class="unit-text">秒（每段线程的爬坡时长，建议不大于每步持续）</span>
            </el-form-item>
            <el-form-item label="峰值">
              <el-input-number v-model="form.peak" :min="1" :step="10" />
              <span class="unit-text">{{ steppedUnitName }}</span>
            </el-form-item>
            <el-form-item label="封顶持续">
              <el-input-number v-model="form.peakSeconds" :min="1" :step="60" />
              <span class="unit-text">秒（升至峰值后保持时长）</span>
            </el-form-item>
          </el-form>
          <!-- 阶梯时间线预览：按公式推导总时长，非整除时给出提示 -->
          <el-alert
            type="info"
            :closable="false"
            show-icon
            :title="steppedPreview.text"
            class="mode-alert"
          />
          <el-alert
            v-if="steppedPreview.warning"
            type="warning"
            :closable="false"
            show-icon
            :title="steppedPreview.warning"
            class="mode-alert"
          />
        </template>

        <el-alert
          v-if="form.mode === 'CONCURRENT'"
          type="info"
          :closable="false"
          show-icon
          title="总并发将按节点数自动均分"
          class="mode-alert"
        />

        <!-- 执行机资源：JMeter 堆内存（可选覆盖节点默认值） -->
        <div class="sub-title section-divider">执行机资源</div>
        <el-form label-width="90px" class="pressure-form">
          <el-form-item label="堆内存">
            <el-input-number
              v-model="form.jmeterHeapMb"
              :min="256"
              :step="512"
              placeholder="默认 2048"
            />
            <span class="unit-text">MB（可选，留空使用节点默认 2048；大压力场景建议调高）</span>
          </el-form-item>
        </el-form>
        <!-- 脚本配置了接口漏斗但当前模式非固定TPS：明确提示将被忽略，避免误解为已生效 -->
        <el-alert
          v-if="form.mode !== 'FIXED_TPS' && scriptFunnelCount"
          type="warning"
          :closable="false"
          show-icon
          class="mode-alert"
          :title="`脚本内 ${scriptFunnelCount} 个串行组接口配置了流量占比，该配置仅在固定TPS模式下生效，当前模式压测将忽略`"
        />
      </section>

      <!-- ③ 流量占比（多执行单元时展示；串行组单元内嵌组内接口的流量漏斗，默认展开可折叠） -->
      <section v-if="showWeights" class="edit-card">
        <div class="card-head">
          <div class="head-left">
            <div>
              <div class="card-title">流量占比</div>
              <div class="card-desc">
                按执行单元分配总压力，各单元占比之和必须等于 100%；
                <template v-if="form.mode === 'FIXED_TPS'">串行组可展开设置组内接口漏斗占比（如登录100→下单60→支付30），默认取脚本值可覆盖</template>
              </div>
            </div>
            <el-button size="small" @click="distributeEvenly">均分</el-button>
          </div>
        </div>
        <div class="weights-list">
          <template v-for="(unit, ui) in execUnits" :key="`${unit.name}-${ui}`">
            <div class="weights-row" :class="{ 'unit-expandable': form.mode === 'FIXED_TPS' && unit.funnelRows?.length }">
              <el-icon
                v-if="form.mode === 'FIXED_TPS' && unit.funnelRows?.length"
                class="unit-arrow"
                :class="{ collapsed: !expandedUnits.has(ui) }"
                @click="toggleUnit(ui)"
              >
                <CaretBottom />
              </el-icon>
              <span v-else class="unit-arrow-spacer"></span>
              <span class="weights-name" :title="unit.name">{{ ui + 1 }}. {{ unit.name }}</span>
              <el-input-number
                v-model="form.weights[ui]"
                :min="1"
                :max="100"
                :step="5"
                size="small"
              />
              <span class="unit-text">%</span>
              <span class="weights-alloc">{{ weightAllocTexts[ui] }}</span>
            </div>
            <!-- 串行组内接口的流量漏斗子行（层级内嵌，不单独成卡） -->
            <div v-if="form.mode === 'FIXED_TPS' && unit.funnelRows?.length && expandedUnits.has(ui)" class="funnel-rows">
              <div v-for="row in unit.funnelRows" :key="row.key" class="weights-row funnel-row">
                <span class="weights-name" :title="row.key">{{ row.name }}</span>
                <el-input-number
                  v-model="form.funnelPercents[row.key]"
                  :min="1"
                  :max="100"
                  :step="5"
                  size="small"
                />
                <span class="unit-text">%</span>
                <span class="weights-alloc">脚本默认 {{ row.defaultPercent }}%</span>
              </div>
            </div>
          </template>
        </div>
        <el-alert
          :type="weightsReady ? 'success' : 'error'"
          :closable="false"
          show-icon
          class="mode-alert"
          :title="
            weightsReady
              ? '占比总和 = 100%，可以提交'
              : `占比总和为 ${weightsTotal}%，各单元占比之和必须等于 100% 才能提交`
          "
        />
        <el-alert
          v-for="(warn, wi) in weightWarnings"
          :key="wi"
          type="warning"
          :closable="false"
          show-icon
          :title="warn"
          class="mode-alert"
        />
        <el-alert
          v-if="steppedUnitsHint"
          type="warning"
          :closable="false"
          show-icon
          :title="steppedUnitsHint"
          class="mode-alert"
        />
      </section>

      <!-- ④ 选择压测节点 -->
      <section class="edit-card">
        <div class="card-head">
          <div>
            <div class="card-title">选择压测节点</div>
            <div class="card-desc">勾选参与压测的执行机（至少 1 个）</div>
          </div>
        </div>
        <el-table
          ref="nodeTableRef"
          v-loading="form.nodeLoading"
          :data="nodeRows"
          max-height="260"
          row-key="nodeKey"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="46" reserve-selection />
          <el-table-column prop="hostname" label="主机名" min-width="140" show-overflow-tooltip />
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ONLINE' ? '在线' : '离线' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="标签" min-width="180">
            <template #default="{ row }">
              <template v-if="row.labels && row.labels.length">
                <el-tag
                  v-for="tag in row.labels"
                  :key="tag"
                  size="small"
                  effect="plain"
                  class="label-tag"
                >
                  {{ tag }}
                </el-tag>
              </template>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
        <!-- 离线节点预警：已勾选节点含离线节点时提示（不阻断保存，启动时后端校验拦截） -->
        <el-alert
          v-if="offlineSelectedText"
          type="warning"
          :closable="false"
          show-icon
          :title="`已选节点当前离线：${offlineSelectedText}。启动任务前请等待节点上线或取消勾选`"
          class="mode-alert"
        />
      </section>

      <!-- ⑤ 参数文件分发 -->
      <section v-if="versionFiles.length" class="edit-card">
        <div class="card-head">
          <div>
            <div class="card-title">参数文件分发</div>
            <div class="card-desc">脚本版本关联的参数文件如何下发到各执行节点</div>
          </div>
        </div>
        <div v-for="file in versionFiles" :key="file.fileId" class="file-dispatch-item">
          <span class="file-name" :title="file.name">{{ file.name }}</span>
          <el-radio-group v-model="form.dispatchModes[file.fileId]" size="small">
            <el-radio value="SHARED">公用（SHARED）</el-radio>
            <el-radio value="SPLIT">拆分（SPLIT）</el-radio>
          </el-radio-group>
          <span v-if="form.dispatchModes[file.fileId] === 'SPLIT'" class="split-hint-inline">
            按行均分到各节点保证参数唯一
          </span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CaretBottom, Check } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { create as createTask, updateTask, detail as taskDetail } from '@/api/task'
import { page as pageScripts, detail as scriptDetail } from '@/api/script'
import { page as pageNodes } from '@/api/node'
import { page as pageFiles } from '@/api/file'

const route = useRoute()
const router = useRouter()

/** 编辑模式（/tasks/:id/edit），否则新建模式 */
const isEdit = computed(() => !!route.params.id)

/** 页面整体加载中（编辑模式拉取详情时） */
const loading = ref(false)

/** 各压测模式的配置默认值（切换模式时重置为对应默认） */
const MODE_DEFAULT_CONFIG = {
  CONCURRENT: { threads: 100, rampupSeconds: 60, durationSeconds: 300 },
  FIXED_TPS: { tps: 500, rampupSeconds: 10, durationSeconds: 300, maxThreads: null },
  STEPPED: { unit: 'THREADS', start: 10, step: 10, stepSeconds: 120, peak: 100, peakSeconds: 600, rampupSeconds: 5 }
}

/** 全部模式配置字段默认值合并（打开页面时初始化 / 编辑时作为兜底） */
const ALL_MODE_DEFAULTS = {
  ...MODE_DEFAULT_CONFIG.CONCURRENT,
  ...MODE_DEFAULT_CONFIG.FIXED_TPS,
  ...MODE_DEFAULT_CONFIG.STEPPED
}

/** config 中可能出现的全部字段名（编辑任务时用于回填，weights 为执行单元占比数组，funnelPercents 为接口漏斗占比） */
const CONFIG_FIELDS = [
  'threads',
  'rampupSeconds',
  'durationSeconds',
  'tps',
  'maxThreads',
  'unit',
  'start',
  'step',
  'stepSeconds',
  'peak',
  'peakSeconds',
  'weights',
  'funnelPercents',
  'jmeterHeapMb'
]

/** 任务表单整体状态（新建/编辑共用；step 为阶梯压测步长字段，与步骤无关） */
const form = reactive({
  submitting: false,
  name: '',
  scriptId: null,
  version: null,
  mode: 'CONCURRENT',
  triggerType: 'MANUAL',
  scheduledStartTime: null,
  dispatchModes: {},
  weights: [],
  funnelPercents: {},
  jmeterHeapMb: null,
  scriptLoading: false,
  versionLoading: false,
  nodeLoading: false,
  ...ALL_MODE_DEFAULTS
})

/** 已提交标记（离开页面确认用） */
const saved = ref(false)

/** 脚本下拉选项 / 当前脚本的版本列表 / 文件下拉数据（名称映射用） */
const scriptOptions = ref([])
const scriptVersions = ref([])
const fileOptions = ref([])

/** 当前脚本的表单定义（执行单元展开用；非 FORM 脚本为 null） */
const scriptFormDef = ref(null)

/** 节点表格数据、已勾选节点与表格实例 */
const nodeRows = ref([])
const selectedNodes = ref([])
const nodeTableRef = ref(null)

/** 当前选中的脚本对象（类型判断用） */
const selectedScript = computed(() =>
  scriptOptions.value.find((item) => item.id === form.scriptId)
)

/** 当前选中脚本是否为导入 JMX 脚本（导入脚本仅支持并发模式） */
const isImportedScript = computed(() => selectedScript.value?.type === 'IMPORTED')

/** 已勾选节点中当前离线的节点名（主机名+IP）：预警用，启动时后端会阻断 */
const offlineSelectedText = computed(() => {
  const offline = selectedNodes.value.filter((node) => node.status !== 'ONLINE')
  if (!offline.length) {
    return ''
  }
  return offline.map((node) => `${node.hostname || node.nodeKey}（${node.ip || '-'}）`).join('、')
})

/** 阶梯单位展示名：THREADS → 并发，TPS → TPS */
const steppedUnitName = computed(() => (form.unit === 'TPS' ? 'TPS' : '并发'))

/**
 * 执行单元列表：
 * - FORM 脚本：与后端 FormScriptJmxBuilder.expandUnits 镜像——SERIAL 组整体 1 个单元，
 *   PARALLEL 组内每个接口 1 个单元；兼容旧 formDef 无 groups 结构
 * - IMPORTED 脚本：当前版本的 JMX 线程组列表（排除 setUp/tearDown）
 */
const execUnits = computed(() => {
  if (selectedScript.value?.type === 'IMPORTED') {
    const current = scriptVersions.value.find((v) => v.version === form.version)
    return (current?.threadGroups || []).map((name) => ({ name }))
  }
  if (selectedScript.value?.type !== 'FORM' || !scriptFormDef.value) {
    return []
  }
  const def = scriptFormDef.value
  const groups = Array.isArray(def.groups) && def.groups.length
    ? def.groups
    : (def.samplers?.length ? [{ name: '', execution: 'SERIAL', samplers: def.samplers }] : [])
  const units = []
  groups.forEach((group) => {
    const groupLabel = (group.name || '').trim() || '接口组'
    if (group.execution === 'PARALLEL') {
      (group.samplers || []).forEach((sampler) => {
        const samplerName = (sampler.name || '').trim() || sampler.url || ''
        units.push({ name: `${groupLabel}-${samplerName}` })
      })
    } else {
      // 串行组单元携带组内接口列表（流量漏斗内嵌展示用；key 与后端 overrideFunnelPercents 匹配规则一致）
      const funnelRows = (group.samplers || []).map((sampler) => {
        const samplerName = (sampler.name || '').trim() || sampler.url || ''
        return {
          key: `${groupLabel}/${samplerName}`,
          group: groupLabel,
          name: samplerName,
          defaultPercent: sampler.trafficPercent ?? 100
        }
      })
      units.push({ name: groupLabel, funnelRows })
    }
  })
  return units
})

/** 是否显示流量占比配置：执行单元数 ≥ 2（单单元无拆分意义，与后端校验口径一致） */
const showWeights = computed(() => execUnits.value.length >= 2)

/**
 * 串行组接口列表（接口级流量漏斗配置项，从执行单元派生）：
 * 仅 FIXED_TPS 模式生效，key=「组名/接口名」（与后端 overrideFunnelPercents 匹配规则一致）
 */
const funnelSamplers = computed(() => {
  if (form.mode !== 'FIXED_TPS') {
    return []
  }
  return execUnits.value.flatMap((unit) => unit.funnelRows || [])
})

/** 脚本内配置了漏斗占比（<100）的串行组接口数：非 FIXED_TPS 模式时提示将被忽略 */
const scriptFunnelCount = computed(() =>
  execUnits.value.reduce(
    (count, unit) => count + (unit.funnelRows || []).filter((row) => row.defaultPercent < 100).length,
    0
  )
)

/** 漏斗配置项变化时补齐缺失默认值（编辑回填已存在的值不覆盖；切换脚本后旧 key 在提交时过滤） */
watch(funnelSamplers, (rows) => {
  rows.forEach((row) => {
    if (form.funnelPercents[row.key] == null) {
      form.funnelPercents[row.key] = row.defaultPercent
    }
  })
}, { immediate: true })

/** 已展开的串行组单元下标（默认全部展开，可逐个折叠） */
const expandedUnits = ref(new Set())

/** 执行单元变化时重置展开状态：带接口的单元默认全展开 */
watch(execUnits, (units) => {
  const next = new Set()
  units.forEach((unit, ui) => {
    if (unit.funnelRows?.length) {
      next.add(ui)
    }
  })
  expandedUnits.value = next
})

/**
 * 切换单元展开/折叠状态
 * @param {number} ui 单元下标
 */
function toggleUnit(ui) {
  const next = new Set(expandedUnits.value)
  if (next.has(ui)) {
    next.delete(ui)
  } else {
    next.add(ui)
  }
  expandedUnits.value = next
}

/** 占比总和（未填项按 0 计） */
const weightsTotal = computed(() =>
  form.weights.reduce((sum, w) => sum + (Number(w) || 0), 0)
)

/** 占比是否就绪：数量与执行单元一致且总和恰为 100 */
const weightsReady = computed(() =>
  execUnits.value.length >= 2 &&
  form.weights.length === execUnits.value.length &&
  weightsTotal.value === 100
)

/**
 * 阶梯展开段数（与后端 SteppedPlan.expand 口径一致）：
 * peak > start 时为 ceil((peak-start)/step) + 1 段，peak == start 时退化为 1 段
 */
const steppedSegmentCount = computed(() => {
  const { start, step, peak } = form
  if (!(Number(start) > 0) || !(Number(step) > 0) || !(Number(peak) > 0) || peak < start) {
    return 0
  }
  return peak > start ? Math.ceil((peak - start) / step) + 1 : 1
})

/**
 * 各执行单元的分配结果预览文案（按当前模式换算，数值为近似值）
 */
const weightAllocTexts = computed(() => {
  if (!showWeights.value || !weightsReady.value) {
    return execUnits.value.map(() => '')
  }
  const nodeCount = selectedNodes.value.length || 1
  const weights = form.weights
  if (form.mode === 'CONCURRENT') {
    const threads = Number(form.threads) || 0
    return execUnits.value.map((_unit, u) => {
      const unitThreads = Math.floor((threads * weights[u]) / 100)
      return `≈ ${unitThreads} 线程，每节点 ${Math.floor(unitThreads / nodeCount)}`
    })
  }
  if (form.mode === 'FIXED_TPS') {
    const tps = Number(form.tps) || 0
    return execUnits.value.map((_unit, u) => {
      const unitTpsPerMin = Math.floor(((tps * 60) * weights[u]) / 100)
      return `≈ ${Math.round(unitTpsPerMin / 60)} TPS，每节点 ${Math.round(Math.floor(unitTpsPerMin / nodeCount) / 60)} TPS`
    })
  }
  const unitName = steppedUnitName.value
  const peak = Number(form.peak) || 0
  return execUnits.value.map((_unit, u) =>
    `峰值段 ≈ ${Math.floor((peak * weights[u]) / 100)} ${unitName}`
  )
})

/**
 * 占比过小的实时预警（后端 validateMinSplit 的前置提示，仅并发/固定TPS模式）
 */
const weightWarnings = computed(() => {
  if (!showWeights.value || !weightsReady.value || form.mode === 'STEPPED') {
    return []
  }
  const nodeCount = selectedNodes.value.length
  if (!nodeCount) {
    return []
  }
  const weights = form.weights
  if (form.mode === 'CONCURRENT') {
    const threads = Number(form.threads) || 0
    return execUnits.value
      .map((unit, u) => {
        const perNode = Math.floor(Math.floor((threads * weights[u]) / 100) / nodeCount)
        if (perNode >= 1) {
          return null
        }
        const minTotal = Math.ceil((nodeCount * 100) / weights[u])
        return `「${unit.name}」按 ${weights[u]}% 分到每节点 ${perNode} 线程（至少 1），请将总并发提升至 ${minTotal} 以上或调高占比`
      })
      .filter(Boolean)
  }
  const tps = Number(form.tps) || 0
  return execUnits.value
    .map((unit, u) => {
      const perNodeTpsPerMin = Math.floor(Math.floor(((tps * 60) * weights[u]) / 100) / nodeCount)
      if (perNodeTpsPerMin >= 1) {
        return null
      }
      const minTps = Math.ceil((nodeCount * 100) / weights[u] / 60)
      return `「${unit.name}」按 ${weights[u]}% 分到每节点不足 1 TPS，请将目标 TPS 提升至 ${minTps} 以上或调高占比`
    })
    .filter(Boolean)
})

/**
 * 阶梯模式组数提示：段数 × 单元数过多时（>20）提示线程组数量
 */
const steppedUnitsHint = computed(() => {
  if (form.mode !== 'STEPPED' || !showWeights.value) {
    return ''
  }
  const segments = steppedSegmentCount.value
  const count = segments * execUnits.value.length
  return count > 20
    ? `当前阶梯将展开 ${segments} 段 × ${execUnits.value.length} 个执行单元 = ${count} 个线程组，组数过多可能影响施压机性能，建议减少段数或合并并行接口`
    : ''
})

/**
 * 阶梯时间线预览：总时长=(peak-start)/step*stepSeconds+peakSeconds；
 * 非整除时提示最后一段为部分增量；每步持续小于爬坡时长时提示连续上升形态
 */
const steppedPreview = computed(() => {
  const { start, step, stepSeconds, peak, peakSeconds } = form
  if (
    [start, step, stepSeconds, peak, peakSeconds].some((value) => !(Number(value) > 0)) ||
    peak < start
  ) {
    return { text: '请先完整填写阶梯参数（峰值需不小于起始值）', warning: '' }
  }
  const span = peak - start
  const divisible = span % step === 0
  const steps = Math.ceil(span / step)
  const total = steps * stepSeconds + peakSeconds
  const unitName = steppedUnitName.value
  const warnings = []
  if (!divisible) {
    warnings.push('步长无法整除，最后一段为部分增量')
  }
  const ramp = Number(form.rampupSeconds) || 0
  if (stepSeconds < ramp) {
    warnings.push(`每步持续 ${stepSeconds}s 小于每段启动爬坡 ${ramp}s，各段未达稳态即切换，曲线将呈连续上升而非台阶；建议每步持续 ≥ 启动时长`)
  }
  return {
    text: `${start} ${unitName}起步，每 ${stepSeconds}s +${step} ${unitName}，升至 ${peak} 后保持 ${peakSeconds}s，总时长 ${total}s`,
    warning: warnings.join('；')
  }
})

/** 当前版本关联的文件列表：解析 fileIds（形如 "1,2"）并映射文件名称 */
const versionFiles = computed(() => {
  const ver = scriptVersions.value.find(
    (item) => Number(item.version) === Number(form.version)
  )
  if (!ver || !ver.fileIds) {
    return []
  }
  return String(ver.fileIds)
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((id) => Number.isFinite(id) && id > 0)
    .map((id) => {
      const file = fileOptions.value.find((item) => Number(item.id) === id)
      return { fileId: id, name: file ? file.name : `文件 #${id}` }
    })
})

/**
 * 版本下拉选项文案：v{version}，存在备注时附加备注
 * @param {Object} item 版本对象
 * @returns {string} 选项 label
 */
function versionLabel(item) {
  return item.remark ? `v${item.version}（${item.remark}）` : `v${item.version}`
}

/**
 * 定时时间选择器的禁用规则：禁用今天之前的日期
 * @param {Date} date 面板中的候选日期
 * @returns {boolean} true 表示禁用
 */
function disabledPastDate(date) {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  return date.getTime() < startOfToday
}

/**
 * 校验定时执行时间：必填且必须大于当前时间
 * @returns {string} 校验失败的提示文案，空串表示通过
 */
function validateScheduleTime() {
  if (!form.scheduledStartTime) {
    return '请选择定时执行时间'
  }
  // Safari 等浏览器不兼容 'YYYY-MM-DD HH:mm:ss' 直接解析，替换为斜杠分隔
  const ts = new Date(String(form.scheduledStartTime).replace(/-/g, '/')).getTime()
  if (!Number.isFinite(ts) || ts <= Date.now()) {
    return '定时执行时间必须大于当前时间'
  }
  return ''
}

/**
 * 生成 n 个单元的均分占比（总和 100，余数补给靠前单元，与后端 WeightSplitter 一致）
 * @param {number} n 单元数量
 * @returns {Array<number>} 占比数组
 */
function rebalanceWeights(n) {
  if (!(n > 0)) {
    return []
  }
  const base = Math.floor(100 / n)
  const remainder = 100 - base * n
  return Array.from({ length: n }, (_, i) => base + (i < remainder ? 1 : 0))
}

/**
 * 均分快捷按钮：将各执行单元占比重置为均分值
 */
function distributeEvenly() {
  form.weights = rebalanceWeights(execUnits.value.length)
}

// 执行单元数量变化时重建占比（数量一致时保留——编辑回填场景不覆盖已保存的占比）
watch(execUnits, (units) => {
  if (form.weights.length === units.length) {
    return
  }
  form.weights = rebalanceWeights(units.length)
})

// 导入脚本仅支持并发模式：脚本切换为导入类型时强制回落到并发模式
watch(isImportedScript, (imported) => {
  if (imported && form.mode !== 'CONCURRENT') {
    form.mode = 'CONCURRENT'
  }
})

// 关联文件变化时，为每个文件初始化默认分发模式 SHARED
watch(versionFiles, (files) => {
  files.forEach((file) => {
    if (!form.dispatchModes[file.fileId]) {
      form.dispatchModes[file.fileId] = 'SHARED'
    }
  })
})

/**
 * 压测模式切换：将该模式的配置字段重置为默认值，避免携带其他模式的残留配置
 */
function handleModeChange() {
  Object.assign(form, MODE_DEFAULT_CONFIG[form.mode])
}

/**
 * 脚本切换：清空已选版本、分发模式与流量占比，拉取脚本详情
 * （版本列表 + 表单定义）并默认选中最新版本
 */
async function handleScriptChange() {
  if (isEdit.value) {
    return
  }
  form.version = null
  scriptVersions.value = []
  form.dispatchModes = {}
  scriptFormDef.value = null
  form.weights = []
  if (!form.scriptId) {
    return
  }
  form.versionLoading = true
  try {
    const res = await scriptDetail(form.scriptId)
    scriptVersions.value = res.data?.versions || []
    scriptFormDef.value = res.data?.formDef || null
    if (scriptVersions.value.length) {
      // 默认选中版本号最大的版本（最新）
      const latest = scriptVersions.value.reduce((a, b) =>
        Number(b.version) > Number(a.version) ? b : a
      )
      form.version = latest.version
    }
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    form.versionLoading = false
  }
}

/**
 * 节点表格勾选变化回调：同步已选节点集合
 * @param {Array} selection 当前勾选的节点行
 */
function handleSelectionChange(selection) {
  selectedNodes.value = selection
}

/**
 * 加载基础下拉数据：脚本 / 节点 / 文件库（并行请求）
 */
async function loadOptions() {
  form.scriptLoading = true
  form.nodeLoading = true
  try {
    const [scriptRes, nodeRes, fileRes] = await Promise.all([
      pageScripts({ page: 1, size: 100 }),
      pageNodes({ page: 1, size: 100 }),
      pageFiles({ page: 1, size: 100 })
    ])
    scriptOptions.value = scriptRes.data?.records || []
    nodeRows.value = nodeRes.data?.records || []
    fileOptions.value = fileRes.data?.records || []
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    form.scriptLoading = false
    form.nodeLoading = false
  }
}

/**
 * 编辑模式加载任务详情并回填表单：名称/模式/压力配置/节点勾选/文件分发
 */
async function loadTask() {
  loading.value = true
  try {
    const res = await taskDetail(route.params.id)
    const task = res.data || null
    if (!task) {
      router.replace('/tasks')
      return
    }
    // 状态兜底：仅 CREATED 任务可编辑（列表入口已控制，此处防御直达 URL）
    if (task.status && task.status !== 'CREATED') {
      ElMessage.warning('仅「已创建」状态的任务可编辑')
      router.replace(`/tasks/${route.params.id}`)
      return
    }
    fillFromTask(task)
    // 拉取脚本详情（版本列表 + 表单定义，用于执行单元展开）
    try {
      const versionRes = await scriptDetail(task.scriptId)
      scriptVersions.value = versionRes.data?.versions || []
      scriptFormDef.value = versionRes.data?.formDef || null
    } catch {
      // 脚本可能已删除：版本与执行单元降级为空
    }
    // 回勾任务已选节点
    await nextTick()
    const nodeKeys = task.nodeKeys || []
    nodeRows.value.forEach((nodeRow) => {
      if (nodeKeys.includes(nodeRow.nodeKey)) {
        nodeTableRef.value?.toggleRowSelection(nodeRow, true)
      }
    })
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
    router.replace('/tasks')
  } finally {
    loading.value = false
  }
}

/**
 * 将任务详情数据回填到表单（名称/模式/配置/执行方式/文件分发）
 * @param {Object} task 任务详情
 */
function fillFromTask(task) {
  form.name = task.name || ''
  form.scriptId = task.scriptId ?? null
  form.version = task.scriptVersion ?? null
  form.mode = ['CONCURRENT', 'FIXED_TPS', 'STEPPED'].includes(task.mode) ? task.mode : 'CONCURRENT'
  form.triggerType = task.triggerType === 'SCHEDULED' ? 'SCHEDULED' : 'MANUAL'
  form.scheduledStartTime = task.scheduledStartTime || null
  // 回填任务已保存的模式参数（仅覆盖存在的字段，其余保持默认）
  const config = task.config || {}
  CONFIG_FIELDS.forEach((key) => {
    if (config[key] !== undefined && config[key] !== null) {
      form[key] = config[key]
    }
  })
  // 回填文件分发模式
  if (Array.isArray(task.fileDispatch)) {
    const dispatchModes = {}
    task.fileDispatch.forEach((item) => {
      if (item && item.fileId) {
        dispatchModes[item.fileId] = item.mode || 'SHARED'
      }
    })
    form.dispatchModes = dispatchModes
  }
}

/**
 * 按当前压测模式组装 config（严格只包含该模式的字段）
 * @returns {Object} 模式对应的 config 对象
 */
function buildModeConfig() {
  if (form.mode === 'CONCURRENT') {
    return {
      threads: form.threads,
      rampupSeconds: form.rampupSeconds,
      durationSeconds: form.durationSeconds
    }
  }
  if (form.mode === 'FIXED_TPS') {
    const config = {
      tps: form.tps,
      rampupSeconds: form.rampupSeconds,
      durationSeconds: form.durationSeconds
    }
    // 线程上限可选：留空时不提交，由服务端按默认规则推导
    if (form.maxThreads) {
      config.maxThreads = form.maxThreads
    }
    // 接口流量漏斗：按当前脚本串行组接口 key 采集有效值（切换脚本后的残留 key 过滤掉）
    const funnel = {}
    funnelSamplers.value.forEach((row) => {
      const percent = form.funnelPercents[row.key]
      if (percent != null && percent > 0 && percent <= 100) {
        funnel[row.key] = Number(percent)
      }
    })
    if (Object.keys(funnel).length) {
      config.funnelPercents = funnel
    }
    return config
  }
  return {
    unit: form.unit,
    start: form.start,
    step: form.step,
    stepSeconds: form.stepSeconds,
    rampupSeconds: form.rampupSeconds,
    peak: form.peak,
    peakSeconds: form.peakSeconds
  }
}

/**
 * 提交（创建或保存）：组装请求体前先做全量校验
 * （名称/脚本/版本/节点/定时时间/阶梯峰值/占比总和），
 * 成功后跳转任务详情页
 */
async function handleSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入任务名称')
    return
  }
  if (!form.scriptId) {
    ElMessage.warning('请选择压测脚本')
    return
  }
  if (form.version === null || form.version === undefined || form.version === '') {
    ElMessage.warning('请选择脚本版本')
    return
  }
  if (!selectedNodes.value.length) {
    ElMessage.warning('请至少选择 1 个压测节点')
    return
  }
  if (form.triggerType === 'SCHEDULED') {
    const scheduleError = validateScheduleTime()
    if (scheduleError) {
      ElMessage.warning(scheduleError)
      return
    }
  }
  if (form.mode === 'STEPPED' && Number(form.peak) < Number(form.start)) {
    ElMessage.warning('阶梯模式的峰值不能小于起始值')
    return
  }
  // 多执行单元：提交前校验占比总和（与后端 validateWeights 口径一致）
  if (showWeights.value && !weightsReady.value) {
    ElMessage.warning('请将各执行单元的流量占比调整为总和 100%')
    return
  }
  form.submitting = true
  try {
    const config = buildModeConfig()
    // 启用流量占比时按执行单元顺序提交占比数组（单单元不传，由后端均分）
    if (showWeights.value && weightsReady.value) {
      config.weights = [...form.weights]
    }
    // JMeter 堆内存可选覆盖：留空时不提交
    if (form.jmeterHeapMb) {
      config.jmeterHeapMb = form.jmeterHeapMb
    }
    const body = {
      name: form.name.trim(),
      scriptId: form.scriptId,
      version: form.version,
      mode: form.mode,
      config,
      nodeKeys: selectedNodes.value.map((node) => node.nodeKey),
      fileDispatch: versionFiles.value.map((file) => ({
        fileId: file.fileId,
        mode: form.dispatchModes[file.fileId] || 'SHARED'
      })),
      triggerType: form.triggerType
    }
    // 定时执行才提交定时时间，立即执行由后端手动触发
    if (form.triggerType === 'SCHEDULED') {
      body.scheduledStartTime = form.scheduledStartTime
    }
    let taskId = route.params.id
    if (isEdit.value) {
      await updateTask(route.params.id, body)
      ElMessage.success('任务已更新')
    } else {
      const res = await createTask(body)
      taskId = res.data?.id || ''
      ElMessage.success(
        form.triggerType === 'SCHEDULED'
          ? `任务已创建，将于 ${form.scheduledStartTime} 自动执行`
          : '任务已创建，请在任务中心点击启动开始压测'
      )
    }
    saved.value = true
    router.replace(`/tasks/${taskId}`)
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    form.submitting = false
  }
}

/**
 * 返回任务中心：未提交修改时二次确认
 */
async function goBack() {
  if (!saved.value) {
    try {
      await ElMessageBox.confirm('当前编辑内容尚未保存，离开后将丢失，是否继续？', '离开确认', {
        type: 'warning',
        confirmButtonText: '离开',
        cancelButtonText: '继续编辑'
      })
    } catch {
      return
    }
  }
  if (window.history.length > 1) {
    router.back()
  } else {
    router.replace('/tasks')
  }
}

// 页面挂载：加载基础下拉；编辑模式追加任务详情回填
onMounted(async () => {
  await loadOptions()
  if (isEdit.value) {
    await loadTask()
  }
})
</script>

<style scoped>
.edit-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  margin: -20px -24px;
  background-color: var(--pp-bg);
}

/* ===== 顶部工具栏 ===== */
.edit-header {
  height: 54px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background-color: var(--pp-surface);
  border-bottom: 1px solid var(--pp-border);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.page-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--pp-text-primary);
  white-space: nowrap;
}
.name-tag {
  flex-shrink: 0;
}

/* ===== 内容区：单列居中卡片流 ===== */
.edit-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 24px 40px;
}

.edit-card {
  background-color: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius);
  box-shadow: var(--pp-shadow);
  padding: 18px 20px;
  margin: 0 auto 16px;
  max-width: 980px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.head-left {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--pp-text-primary);
}
.card-desc {
  font-size: 12px;
  color: var(--pp-text-secondary);
  margin-top: 3px;
  line-height: 1.5;
}

/* 表单字段横排行 */
.form-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0 24px;
}
.form-row .el-form-item {
  margin-bottom: 12px;
  flex: 1 1 240px;
  max-width: 400px;
}
.form-row .row-span-2 {
  flex: 1 1 280px;
  max-width: 360px;
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--pp-text-regular);
  margin: 14px 0 8px;
}
.sub-title.section-divider {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--pp-border);
}

.pressure-form {
  margin-bottom: 12px;
}
.unit-text {
  margin-left: 8px;
  font-size: 13px;
  color: var(--pp-text-secondary);
}
.mode-alert {
  margin-top: 10px;
}

.label-tag {
  margin-right: 6px;
  margin-bottom: 2px;
}

/* 流量占比 */
.weights-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.weights-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.weights-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--pp-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.weights-alloc {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--pp-text-secondary);
}

/* 串行组单元展开箭头与接口漏斗子行 */
.unit-arrow {
  flex-shrink: 0;
  width: 18px;
  font-size: 14px;
  color: var(--pp-text-secondary);
  cursor: pointer;
  transition: transform 0.15s ease;
}
.unit-arrow.collapsed {
  transform: rotate(-90deg);
}
.unit-arrow-spacer {
  flex-shrink: 0;
  width: 18px;
}
.unit-expandable .weights-name {
  font-weight: 600;
}
.funnel-rows {
  padding-left: 26px;
  border-left: 2px solid var(--pp-border);
  margin: 2px 0 6px 9px;
}
.funnel-row .weights-name {
  font-size: 12.5px;
  color: var(--pp-text-regular);
}

/* 参数文件分发行 */
.file-dispatch-item {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 8px 12px;
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius-sm);
  margin-bottom: 8px;
}
.file-name {
  font-size: 13px;
  color: var(--pp-text-primary);
  font-weight: 500;
  word-break: break-all;
}
.split-hint-inline {
  font-size: 12px;
  color: var(--pp-warning);
}

.w-160 {
  width: 160px;
}
.w-full {
  width: 100%;
}
</style>
