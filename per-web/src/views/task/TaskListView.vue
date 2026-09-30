<!--
  任务中心列表页（核心页面）：
  - 关键字（任务名/编号）与状态筛选、分页查询
  - 任务列表展示：任务号（等宽字体）/模式/执行方式（手动/定时+定时时间）/脚本版本/节点数/状态（按状态着色）/起止时间等
  - 行内操作：详情跳转、编辑（仅 CREATED，复用创建向导）、启动（仅 CREATED 且非定时）、
    改为立即执行（CREATED 且 SCHEDULED，覆盖定时设置）、停止（PREPARING/RUNNING/STOPPING），均二次确认
  - 创建/编辑弹窗（单页两栏布局）：左栏=基本信息+压测模式；右栏=选择节点+压力配置+流量占比；底部=参数文件分发；
    压力步骤支持三种压测模式（CONCURRENT 并发 / FIXED_TPS 固定TPS / STEPPED 阶梯），
    导入脚本（IMPORTED）仅支持并发模式，阶梯模式提供时间线文字预览；
    FORM 脚本存在多个执行单元（串行组 / 并行接口）时提供「流量占比」配置：
    每单元百分比输入 + 总和 =100% 阻断校验 + 均分快捷键 + 分配结果预览与最小压力预警；
    基本信息支持执行方式选择：立即执行（MANUAL）/ 定时执行（SCHEDULED，需选择未来的定时时间）
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
        <el-button type="primary" :icon="Plus" @click="openWizard">新建压测任务</el-button>
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
              @click="openEditWizard(row)"
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

    <!-- 创建/编辑压测任务（单页单列布局：字段横排分区纵向排列，宽弹窗内滚） -->
    <el-dialog
      v-model="wizard.visible"
      :title="wizard.editMode ? '编辑任务' : '新建压测任务'"
      width="1200px"
      top="3vh"
      class="task-create-dialog"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div class="wizard-page">
        <!-- ① 基本信息：字段横排两行（名称/脚本/版本 + 执行方式/定时时间） -->
        <div class="pane-title">基本信息</div>
        <el-alert
          v-if="wizard.editMode"
          type="info"
          :closable="false"
          show-icon
          title="编辑任务时脚本与版本不可修改"
          class="mode-alert"
        />
        <el-form label-width="80px" class="grid-form">
          <div class="form-row">
            <el-form-item label="任务名称" required>
              <el-input
                v-model="wizard.name"
                placeholder="请输入任务名称"
                maxlength="64"
                clearable
              />
            </el-form-item>
            <el-form-item label="压测脚本" required>
              <el-select
                v-model="wizard.scriptId"
                placeholder="请选择压测脚本"
                :loading="wizard.scriptLoading"
                :disabled="wizard.editMode"
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
                v-model="wizard.version"
                placeholder="请先选择脚本"
                :loading="wizard.versionLoading"
                :disabled="wizard.editMode || !wizard.scriptId"
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
              <el-radio-group v-model="wizard.triggerType">
                <el-radio value="MANUAL">立即执行</el-radio>
                <el-radio value="SCHEDULED">定时执行</el-radio>
              </el-radio-group>
            </el-form-item>
            <!-- 定时执行：选择未来的定时启动时间（小于当前时间不可选） -->
            <el-form-item
              v-if="wizard.triggerType === 'SCHEDULED'"
              label="定时时间"
              required
              class="row-span-2"
            >
              <el-date-picker
                v-model="wizard.scheduledStartTime"
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

        <!-- ② 压测模式与压力配置（模式选择 + 模式参数横排 + 堆内存） -->
        <div class="pane-title">压测模式</div>
        <el-radio-group v-model="wizard.mode" @change="handleModeChange">
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

        <!-- ③ 压力配置：按模式动态渲染（每参数一行，说明文字跟随） -->
        <div class="section-title">压力配置</div>

        <!-- 并发模式 -->
        <el-form v-if="wizard.mode === 'CONCURRENT'" label-width="90px" class="pressure-form">
          <el-form-item label="总并发数">
            <el-input-number v-model="wizard.threads" :min="1" :step="10" />
            <span class="unit-text">线程（按节点均分）</span>
          </el-form-item>
          <el-form-item label="启动时长">
            <el-input-number v-model="wizard.rampupSeconds" :min="0" :step="10" />
            <span class="unit-text">秒（Ramp-up）</span>
          </el-form-item>
          <el-form-item label="持续时长">
            <el-input-number v-model="wizard.durationSeconds" :min="1" :step="30" />
            <span class="unit-text">秒（Duration）</span>
          </el-form-item>
        </el-form>

        <!-- 固定TPS模式 -->
        <el-form v-else-if="wizard.mode === 'FIXED_TPS'" label-width="90px" class="pressure-form">
          <el-form-item label="目标TPS">
            <el-input-number v-model="wizard.tps" :min="1" :step="50" />
            <span class="unit-text">样本数 / 秒</span>
          </el-form-item>
          <el-form-item label="启动时长">
            <el-input-number v-model="wizard.rampupSeconds" :min="0" :step="5" />
            <span class="unit-text">秒（吞吐爬坡到目标 TPS 的时长，0 表示立即满速）</span>
          </el-form-item>
          <el-form-item label="持续时长">
            <el-input-number v-model="wizard.durationSeconds" :min="1" :step="30" />
            <span class="unit-text">秒</span>
          </el-form-item>
          <el-form-item label="线程上限">
            <el-input-number v-model="wizard.maxThreads" :min="1" :step="100" />
            <span class="unit-text">可选，留空默认 min(tps*2, 2000)</span>
          </el-form-item>
        </el-form>

        <!-- 阶梯压测模式 -->
        <template v-else>
          <el-form label-width="90px" class="pressure-form">
            <el-form-item label="阶梯单位">
              <el-select v-model="wizard.unit" class="w-160">
                <el-option label="按并发（THREADS）" value="THREADS" />
                <el-option label="按吞吐（TPS）" value="TPS" />
              </el-select>
            </el-form-item>
            <el-form-item label="起始值">
              <el-input-number v-model="wizard.start" :min="1" :step="5" />
              <span class="unit-text">{{ steppedUnitName }}</span>
            </el-form-item>
            <el-form-item label="步长">
              <el-input-number v-model="wizard.step" :min="1" :step="5" />
              <span class="unit-text">{{ steppedUnitName }} / 每步增量</span>
            </el-form-item>
            <el-form-item label="每步持续">
              <el-input-number v-model="wizard.stepSeconds" :min="1" :step="30" />
              <span class="unit-text">秒</span>
            </el-form-item>
            <el-form-item label="启动时长">
              <el-input-number v-model="wizard.rampupSeconds" :min="0" :step="5" />
              <span class="unit-text">秒（每段线程的爬坡时长，建议不大于每步持续）</span>
            </el-form-item>
            <el-form-item label="峰值">
              <el-input-number v-model="wizard.peak" :min="1" :step="10" />
              <span class="unit-text">{{ steppedUnitName }}</span>
            </el-form-item>
            <el-form-item label="封顶持续">
              <el-input-number v-model="wizard.peakSeconds" :min="1" :step="60" />
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
          v-if="wizard.mode === 'CONCURRENT'"
          type="info"
          :closable="false"
          show-icon
          title="总并发将按节点数自动均分"
          class="mode-alert"
        />

        <!-- 执行机资源：JMeter 堆内存（模式无关，可选覆盖节点默认值） -->
        <div class="section-title">执行机资源</div>
        <el-form label-width="90px" class="pressure-form">
          <el-form-item label="堆内存">
            <el-input-number
              v-model="wizard.jmeterHeapMb"
              :min="256"
              :step="512"
              placeholder="默认 2048"
            />
            <span class="unit-text">MB（可选，留空使用节点默认 2048；大压力场景建议调高）</span>
          </el-form-item>
        </el-form>

        <!-- 流量占比：存在多个执行单元（串行组 / 并行接口 / 导入脚本多线程组）时，按单元分配总压力 -->
        <template v-if="showWeights">
          <div class="section-title weights-title">
            <span>流量占比</span>
            <el-button size="small" @click="distributeEvenly">均分</el-button>
          </div>
          <div class="weights-list">
            <div
              v-for="(unit, ui) in execUnits"
              :key="`${unit.name}-${ui}`"
              class="weights-row"
            >
              <span class="weights-name" :title="unit.name">{{ ui + 1 }}. {{ unit.name }}</span>
              <el-input-number
                v-model="wizard.weights[ui]"
                :min="1"
                :max="100"
                :step="5"
                size="small"
              />
              <span class="unit-text">%</span>
              <span class="weights-alloc">{{ weightAllocTexts[ui] }}</span>
            </div>
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
        </template>

        <!-- ④ 选择压测节点（压力配置之后，勾选参与压测的执行机） -->
        <div class="pane-title">选择压测节点（至少 1 个）</div>
        <el-table
          ref="nodeTableRef"
          v-loading="wizard.nodeLoading"
          :data="nodeRows"
          max-height="240"
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

        <!-- 底部：参数文件分发（脚本版本关联了参数文件时展示，紧凑横排） -->
        <template v-if="versionFiles.length">
          <div class="pane-title">参数文件分发</div>
          <div v-for="file in versionFiles" :key="file.fileId" class="file-dispatch-item">
            <span class="file-name" :title="file.name">{{ file.name }}</span>
            <el-radio-group v-model="wizard.dispatchModes[file.fileId]" size="small">
              <el-radio value="SHARED">公用（SHARED）</el-radio>
              <el-radio value="SPLIT">拆分（SPLIT）</el-radio>
            </el-radio-group>
            <span v-if="wizard.dispatchModes[file.fileId] === 'SPLIT'" class="split-hint-inline">
              按行均分到各节点保证参数唯一
            </span>
          </div>
        </template>
      </div>

      <template #footer>
        <el-button @click="wizard.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="wizard.submitting"
          @click="handleSubmit"
        >
          {{ wizard.editMode ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search, RefreshLeft, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  page as pageTasks,
  create as createTask,
  updateTask,
  startTask,
  stopTask,
  removeTask,
  copyTask
} from '@/api/task'
import { page as pageScripts, detail as scriptDetail } from '@/api/script'
import { page as pageNodes } from '@/api/node'
import { page as pageFiles } from '@/api/file'
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

/** 压测模式元信息：展示文案（列表/向导/摘要共用） */
const TASK_MODE_META = {
  CONCURRENT: '并发模式',
  FIXED_TPS: '固定TPS',
  STEPPED: '阶梯压测'
}

/** 各压测模式的配置默认值（切换模式时重置为对应默认） */
const MODE_DEFAULT_CONFIG = {
  CONCURRENT: { threads: 100, rampupSeconds: 60, durationSeconds: 300 },
  FIXED_TPS: { tps: 500, rampupSeconds: 10, durationSeconds: 300, maxThreads: null },
  STEPPED: { unit: 'THREADS', start: 10, step: 10, stepSeconds: 120, peak: 100, peakSeconds: 600, rampupSeconds: 5 }
}

/** 全部模式配置字段默认值合并（打开向导时初始化 / 编辑时作为兜底） */
const ALL_MODE_DEFAULTS = {
  ...MODE_DEFAULT_CONFIG.CONCURRENT,
  ...MODE_DEFAULT_CONFIG.FIXED_TPS,
  ...MODE_DEFAULT_CONFIG.STEPPED
}

/** config 中可能出现的全部字段名（编辑任务时用于回填，weights 为执行单元占比数组） */
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
  'jmeterHeapMb'
]

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

/** 创建/编辑向导整体状态（editMode=true 时复用为编辑对话框）；
 *  stepIndex 为向导步骤索引（0/1/2），step 为阶梯压测的步长配置——两者不可混用同名字段 */
const wizard = reactive({
  visible: false,
  stepIndex: 0,
  submitting: false,
  editMode: false,
  editId: null,
  name: '',
  scriptId: null,
  version: null,
  mode: 'CONCURRENT',
  triggerType: 'MANUAL',
  scheduledStartTime: null,
  dispatchModes: {},
  weights: [],
  jmeterHeapMb: null,
  scriptLoading: false,
  versionLoading: false,
  nodeLoading: false,
  ...ALL_MODE_DEFAULTS
})

/** 脚本下拉选项 / 当前脚本的版本列表 / 文件下拉数据（用于名称映射） */
const scriptOptions = ref([])
const scriptVersions = ref([])
const fileOptions = ref([])

/** 当前脚本的表单定义（脚本级最新定义，与后端权重校验数据源一致；非 FORM 脚本为 null） */
const scriptFormDef = ref(null)

/** 节点表格数据、已勾选节点与表格实例 */
const nodeRows = ref([])
const selectedNodes = ref([])
const nodeTableRef = ref(null)

/** 当前选中的脚本对象（用于摘要展示与类型判断） */
const selectedScript = computed(() =>
  scriptOptions.value.find((item) => item.id === wizard.scriptId)
)

/** 当前选中脚本是否为导入 JMX 脚本（导入脚本仅支持并发模式） */
const isImportedScript = computed(() => selectedScript.value?.type === 'IMPORTED')

/** 已勾选节点中当前离线的节点名（主机名+IP）：用于向导内预警，启动时后端会阻断 */
const offlineSelectedText = computed(() => {
  const offline = selectedNodes.value.filter((node) => node.status !== 'ONLINE')
  if (!offline.length) {
    return ''
  }
  return offline.map((node) => `${node.hostname || node.nodeKey}（${node.ip || '-'}）`).join('、')
})

/** 阶梯单位展示名：THREADS → 并发，TPS → TPS */
const steppedUnitName = computed(() => (wizard.unit === 'TPS' ? 'TPS' : '并发'))

/**
 * 执行单元列表：
 * - FORM 脚本：与后端 FormScriptJmxBuilder.expandUnits 镜像——SERIAL 组整体 1 个单元（组内共享压力），
 *   PARALLEL 组内每个接口 1 个单元（独立施压）；兼容旧 formDef 无 groups 结构（顶层 samplers 包成单个 SERIAL 组）
 * - IMPORTED 脚本：当前版本的 JMX 线程组列表（后端 JmxThreadGroupParser 口径，排除 setUp/tearDown）
 */
const execUnits = computed(() => {
  if (selectedScript.value?.type === 'IMPORTED') {
    const current = scriptVersions.value.find((v) => v.version === wizard.version)
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
      units.push({ name: groupLabel })
    }
  })
  return units
})

/** 是否显示流量占比配置：FORM 脚本且执行单元数 ≥ 2（单单元无拆分意义，与后端校验口径一致） */
const showWeights = computed(() => execUnits.value.length >= 2)

/** 占比总和（未填项按 0 计） */
const weightsTotal = computed(() =>
  wizard.weights.reduce((sum, w) => sum + (Number(w) || 0), 0)
)

/** 占比是否就绪：数量与执行单元一致且总和恰为 100 */
const weightsReady = computed(() =>
  execUnits.value.length >= 2 &&
  wizard.weights.length === execUnits.value.length &&
  weightsTotal.value === 100
)

/**
 * 阶梯展开段数（与后端 SteppedPlan.expand 口径一致）：
 * peak > start 时为 ceil((peak-start)/step) + 1 段，peak == start 时退化为 1 段
 */
const steppedSegmentCount = computed(() => {
  const { start, step, peak } = wizard
  if (!(Number(start) > 0) || !(Number(step) > 0) || !(Number(peak) > 0) || peak < start) {
    return 0
  }
  return peak > start ? Math.ceil((peak - start) / step) + 1 : 1
})

/**
 * 各执行单元的分配结果预览文案（按当前模式换算，数值为近似值）：
 * CONCURRENT → 线程数与每节点线程；FIXED_TPS → TPS 与每节点 TPS；STEPPED → 峰值段分配（各段同比例）
 */
const weightAllocTexts = computed(() => {
  if (!showWeights.value || !weightsReady.value) {
    return execUnits.value.map(() => '')
  }
  const nodeCount = selectedNodes.value.length || 1
  const weights = wizard.weights
  if (wizard.mode === 'CONCURRENT') {
    const threads = Number(wizard.threads) || 0
    return execUnits.value.map((_unit, u) => {
      const unitThreads = Math.floor((threads * weights[u]) / 100)
      return `≈ ${unitThreads} 线程，每节点 ${Math.floor(unitThreads / nodeCount)}`
    })
  }
  if (wizard.mode === 'FIXED_TPS') {
    const tps = Number(wizard.tps) || 0
    return execUnits.value.map((_unit, u) => {
      const unitTpsPerMin = Math.floor(((tps * 60) * weights[u]) / 100)
      return `≈ ${Math.round(unitTpsPerMin / 60)} TPS，每节点 ${Math.round(Math.floor(unitTpsPerMin / nodeCount) / 60)} TPS`
    })
  }
  const unitName = steppedUnitName.value
  const peak = Number(wizard.peak) || 0
  return execUnits.value.map((_unit, u) =>
    `峰值段 ≈ ${Math.floor((peak * weights[u]) / 100)} ${unitName}`
  )
})

/**
 * 占比过小的实时预警（后端 validateMinSplit 的前置提示，仅并发/固定TPS模式）：
 * 单元按占比拆分后再按节点均分，若某单元每节点分到的线程（或 TPS 样本配额）不足 1 则给出总量建议
 */
const weightWarnings = computed(() => {
  if (!showWeights.value || !weightsReady.value || wizard.mode === 'STEPPED') {
    return []
  }
  const nodeCount = selectedNodes.value.length
  if (!nodeCount) {
    return []
  }
  const weights = wizard.weights
  if (wizard.mode === 'CONCURRENT') {
    const threads = Number(wizard.threads) || 0
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
  const tps = Number(wizard.tps) || 0
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
 * 阶梯模式组数提示：段数 × 单元数过多时（>20）提示线程组数量，避免施压机负载过高
 */
const steppedUnitsHint = computed(() => {
  if (wizard.mode !== 'STEPPED' || !showWeights.value) {
    return ''
  }
  const segments = steppedSegmentCount.value
  const count = segments * execUnits.value.length
  return count > 20
    ? `当前阶梯将展开 ${segments} 段 × ${execUnits.value.length} 个执行单元 = ${count} 个线程组，组数过多可能影响施压机性能，建议减少段数或合并并行接口`
    : ''
})

/**
 * 生成 n 个单元的均分占比（总和 100，余数补给靠前单元，与后端 WeightSplitter 行为一致）
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
  wizard.weights = rebalanceWeights(execUnits.value.length)
}

// 执行单元数量变化时重建占比（数量一致时保留——编辑回填场景不覆盖已保存的占比）
watch(execUnits, (units) => {
  if (wizard.weights.length === units.length) {
    return
  }
  wizard.weights = rebalanceWeights(units.length)
})

/**
 * 阶梯时间线预览：按公式总时长=(peak-start)/step*stepSeconds+peakSeconds 推导；
 * (peak-start) 无法被 step 整除时提示最后一段为部分增量（总时长按向上取整步数计算）；
 * 每步持续小于段启动爬坡时长（30 秒）时提示阶梯将呈连续上升形态（段未达稳态即切换）
 */
const steppedPreview = computed(() => {
  const { start, step, stepSeconds, peak, peakSeconds } = wizard
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
  const ramp = Number(wizard.rampupSeconds) || 0
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
    (item) => Number(item.version) === Number(wizard.version)
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
 * 定时时间选择器的禁用规则：禁用今天之前的日期
 * （今天内的过去时刻由 handleNext / handleSubmit 的提交校验拦截）
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
  if (!wizard.scheduledStartTime) {
    return '请选择定时执行时间'
  }
  // Safari 等浏览器不兼容 'YYYY-MM-DD HH:mm:ss' 直接解析，替换为斜杠分隔
  const ts = new Date(String(wizard.scheduledStartTime).replace(/-/g, '/')).getTime()
  if (!Number.isFinite(ts) || ts <= Date.now()) {
    return '定时执行时间必须大于当前时间'
  }
  return ''
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

/**
 * 打开创建向导：重置表单状态（含三种模式默认配置）并并行加载脚本、节点、文件数据
 */
async function openWizard() {
  Object.assign(wizard, {
    visible: true,
    stepIndex: 0,
    submitting: false,
    editMode: false,
    editId: null,
    name: '',
    scriptId: null,
    version: null,
    mode: 'CONCURRENT',
    triggerType: 'MANUAL',
    scheduledStartTime: null,
    dispatchModes: {},
    weights: [],
    jmeterHeapMb: null,
    scriptLoading: true,
    versionLoading: false,
    nodeLoading: true,
    ...ALL_MODE_DEFAULTS
  })
  scriptFormDef.value = null
  scriptVersions.value = []
  selectedNodes.value = []
  await nextTick()
  nodeTableRef.value?.clearSelection()
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
    wizard.scriptLoading = false
    wizard.nodeLoading = false
  }
}

/**
 * 打开编辑任务向导（仅 CREATED 任务）：复用创建向导，
 * 预填名称/模式/压力配置/节点勾选/文件分发；脚本与版本不可修改
 * @param {Object} row 任务行数据
 */
async function openEditWizard(row) {
  Object.assign(wizard, {
    visible: true,
    stepIndex: 0,
    submitting: false,
    editMode: true,
    editId: row.id,
    name: row.name || '',
    scriptId: row.scriptId ?? null,
    version: row.scriptVersion ?? null,
    mode: TASK_MODE_META[row.mode] ? row.mode : 'CONCURRENT',
    triggerType: row.triggerType === 'SCHEDULED' ? 'SCHEDULED' : 'MANUAL',
    scheduledStartTime: row.scheduledStartTime || null,
    dispatchModes: {},
    weights: [],
    jmeterHeapMb: null,
    scriptLoading: true,
    versionLoading: false,
    nodeLoading: true,
    ...ALL_MODE_DEFAULTS
  })
  // 回填任务已保存的模式参数（仅覆盖存在的字段，其余保持默认）
  const config = row.config || {}
  CONFIG_FIELDS.forEach((key) => {
    if (config[key] !== undefined && config[key] !== null) {
      wizard[key] = config[key]
    }
  })
  // 回填文件分发模式
  if (Array.isArray(row.fileDispatch)) {
    const dispatchModes = {}
    row.fileDispatch.forEach((item) => {
      if (item && item.fileId) {
        dispatchModes[item.fileId] = item.mode || 'SHARED'
      }
    })
    wizard.dispatchModes = dispatchModes
  }
  const nodeKeys = row.nodeKeys || []
  scriptVersions.value = []
  selectedNodes.value = []
  await nextTick()
  nodeTableRef.value?.clearSelection()
  try {
    const [scriptRes, nodeRes, fileRes, versionRes] = await Promise.all([
      pageScripts({ page: 1, size: 100 }),
      pageNodes({ page: 1, size: 100 }),
      pageFiles({ page: 1, size: 100 }),
      scriptDetail(row.scriptId)
    ])
    scriptOptions.value = scriptRes.data?.records || []
    nodeRows.value = nodeRes.data?.records || []
    fileOptions.value = fileRes.data?.records || []
    scriptVersions.value = versionRes.data?.versions || []
    // 存储脚本表单定义用于展开执行单元（编辑模式下占比已在上方从 config 回填，长度一致时不覆盖）
    scriptFormDef.value = versionRes.data?.formDef || null
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    wizard.scriptLoading = false
    wizard.nodeLoading = false
  }
  // 回勾任务已选节点
  await nextTick()
  nodeRows.value.forEach((nodeRow) => {
    if (nodeKeys.includes(nodeRow.nodeKey)) {
      nodeTableRef.value?.toggleRowSelection(nodeRow, true)
    }
  })
}

/**
 * 压测模式切换：将该模式的配置字段重置为默认值，避免携带其他模式的残留配置
 */
function handleModeChange() {
  Object.assign(wizard, MODE_DEFAULT_CONFIG[wizard.mode])
}

// 导入脚本仅支持并发模式：脚本切换为导入类型时强制回落到并发模式
watch(isImportedScript, (imported) => {
  if (imported && wizard.mode !== 'CONCURRENT') {
    wizard.mode = 'CONCURRENT'
  }
})

/**
 * 脚本切换：清空已选版本、分发模式与流量占比，拉取脚本详情
 * （版本列表 + 表单定义，formDef 用于展开执行单元）并默认选中最新版本
 */
async function handleScriptChange() {
  if (wizard.editMode) {
    return
  }
  wizard.version = null
  scriptVersions.value = []
  wizard.dispatchModes = {}
  scriptFormDef.value = null
  wizard.weights = []
  if (!wizard.scriptId) {
    return
  }
  wizard.versionLoading = true
  try {
    const res = await scriptDetail(wizard.scriptId)
    scriptVersions.value = res.data?.versions || []
    scriptFormDef.value = res.data?.formDef || null
    if (scriptVersions.value.length) {
      // 默认选中版本号最大的版本（最新）
      const latest = scriptVersions.value.reduce((a, b) =>
        Number(b.version) > Number(a.version) ? b : a
      )
      wizard.version = latest.version
    }
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    wizard.versionLoading = false
  }
}

/**
 * 节点表格勾选变化回调：同步已选节点集合
 * @param {Array} selection 当前勾选的节点行
 */
function handleSelectionChange(selection) {
  selectedNodes.value = selection
}

// 关联文件变化时，为每个文件初始化默认分发模式 SHARED
watch(versionFiles, (files) => {
  files.forEach((file) => {
    if (!wizard.dispatchModes[file.fileId]) {
      wizard.dispatchModes[file.fileId] = 'SHARED'
    }
  })
})

/**
 * 按当前压测模式组装 config（严格只包含该模式的字段，多余字段不提交）
 * @returns {Object} 模式对应的 config 对象
 */
function buildModeConfig() {
  if (wizard.mode === 'CONCURRENT') {
    return {
      threads: wizard.threads,
      rampupSeconds: wizard.rampupSeconds,
      durationSeconds: wizard.durationSeconds
    }
  }
  if (wizard.mode === 'FIXED_TPS') {
    const config = {
      tps: wizard.tps,
      rampupSeconds: wizard.rampupSeconds,
      durationSeconds: wizard.durationSeconds
    }
    // 线程上限可选：留空时不提交，由服务端按默认规则推导
    if (wizard.maxThreads) {
      config.maxThreads = wizard.maxThreads
    }
    return config
  }
  return {
    unit: wizard.unit,
    start: wizard.start,
    step: wizard.step,
    stepSeconds: wizard.stepSeconds,
    rampupSeconds: wizard.rampupSeconds,
    peak: wizard.peak,
    peakSeconds: wizard.peakSeconds
  }
}

/**
 * 提交（创建或保存，单页模式）：组装请求体前先做全量校验
 * （名称/脚本/版本/节点/定时时间/阶梯峰值/占比总和），编辑模式调用更新接口，
 * 创建模式调用创建接口，成功后关闭弹窗并刷新列表
 */
async function handleSubmit() {
  if (!wizard.name.trim()) {
    ElMessage.warning('请输入任务名称')
    return
  }
  if (!wizard.scriptId) {
    ElMessage.warning('请选择压测脚本')
    return
  }
  if (wizard.version === null || wizard.version === undefined || wizard.version === '') {
    ElMessage.warning('请选择脚本版本')
    return
  }
  if (!selectedNodes.value.length) {
    ElMessage.warning('请至少选择 1 个压测节点')
    return
  }
  if (wizard.triggerType === 'SCHEDULED') {
    const scheduleError = validateScheduleTime()
    if (scheduleError) {
      ElMessage.warning(scheduleError)
      return
    }
  }
  if (wizard.mode === 'STEPPED' && Number(wizard.peak) < Number(wizard.start)) {
    ElMessage.warning('阶梯模式的峰值不能小于起始值')
    return
  }
  // FORM 脚本多执行单元：提交前校验占比总和（与后端 validateWeights 口径一致）
  if (showWeights.value && !weightsReady.value) {
    ElMessage.warning('请将各执行单元的流量占比调整为总和 100%')
    return
  }
  wizard.submitting = true
  try {
    const config = buildModeConfig()
    // 启用流量占比时按执行单元顺序提交占比数组（单单元不传，由后端均分）
    if (showWeights.value && weightsReady.value) {
      config.weights = [...wizard.weights]
    }
    // JMeter 堆内存可选覆盖：留空时不提交，由节点 Agent 使用本地默认值
    if (wizard.jmeterHeapMb) {
      config.jmeterHeapMb = wizard.jmeterHeapMb
    }
    const body = {
      name: wizard.name.trim(),
      scriptId: wizard.scriptId,
      version: wizard.version,
      mode: wizard.mode,
      config,
      nodeKeys: selectedNodes.value.map((node) => node.nodeKey),
      fileDispatch: versionFiles.value.map((file) => ({
        fileId: file.fileId,
        mode: wizard.dispatchModes[file.fileId] || 'SHARED'
      })),
      triggerType: wizard.triggerType
    }
    // 定时执行才提交定时时间（格式 YYYY-MM-DD HH:mm:ss），立即执行由后端手动触发
    if (wizard.triggerType === 'SCHEDULED') {
      body.scheduledStartTime = wizard.scheduledStartTime
    }
    if (wizard.editMode) {
      await updateTask(wizard.editId, body)
      ElMessage.success('任务已更新')
    } else {
      await createTask(body)
      ElMessage.success(
        wizard.triggerType === 'SCHEDULED'
          ? `任务已创建，将于 ${wizard.scheduledStartTime} 自动执行`
          : '任务已创建，请点击启动开始压测'
      )
    }
    wizard.visible = false
    page.value = 1
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    wizard.submitting = false
  }
}

// 页面挂载后自动加载任务列表
onMounted(load)
</script>

<style scoped>
/* 工具栏卡与列表卡的间距由全局 .page-card + .page-card 统一控制（16px），此处不再重复声明 */

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

.w-160 {
  width: 160px;
}

.w-full {
  width: 100%;
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

/* 创建弹窗：单页内容区限高纵向滚动，禁止横向溢出 */
.wizard-page {
  max-height: 78vh;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
}

/* 表单字段横排行：多个 el-form-item 同行排布（基本信息），字段弹性伸展保证下拉框宽度 */
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

/* 横排行中的跟随项（定时时间等）可占更多宽 */
.form-row .row-span-2 {
  flex: 1 1 280px;
  max-width: 360px;
}

/* 分栏小节标题（左栏/右栏/底部通用） */
.pane-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--pp-text-primary);
  margin: 16px 0 10px;
  padding-left: 8px;
  border-left: 3px solid var(--pp-primary);
}

.pane-title:first-child {
  margin-top: 0;
}

/* 右栏顶部的节标题与 pane-title 对齐 */
.wizard-page .section-title {
  margin-top: 16px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--pp-text-primary);
  margin: 16px 0 10px;
}

.label-tag {
  margin-right: 6px;
  margin-bottom: 2px;
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

/* 参数文件分发行：横排（文件名 + 分发模式 + 拆分说明） */
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

/* 流量占比区：标题行左右布局（标题 + 均分按钮） */
.weights-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 流量占比行列表 */
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

/* 单元名称：占满剩余空间，过长省略（完整名见 title 悬浮） */
.weights-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--pp-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 分配结果预览文案 */
.weights-alloc {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--pp-text-secondary);
}
</style>
