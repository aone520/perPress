<!--
  脚本详情页（/scripts/:id）：
  - 返回按钮 + 页头操作（FORM 脚本「编辑脚本」跳转全屏编辑页 /scripts/:id/edit，IMPORTED 脚本「新增版本」弹窗编辑 JMX）
  - 基本信息卡（名称/类型/描述/最新版本/创建人/创建时间）
  - FORM 类型展示表单摘要卡（全局配置：目标环境/自定义变量 + 线程组/思考时间 + 采样器表格）
  - 版本列表：版本/备注/关联文件（映射文件名）/创建时间/操作（查看 JMX 只读弹窗、下载 JMX）
-->
<template>
  <div class="page" v-loading="loading">
    <!-- 返回 + 页头操作 -->
    <div class="page-header">
      <div class="page-header-left">
        <span class="page-title">{{ script.name || '脚本详情' }}</span>
        <el-tag v-if="script.type" size="small" effect="plain" :type="script.type === 'FORM' ? 'warning' : 'primary'">
          {{ script.type === 'FORM' ? '表单' : '导入' }}
        </el-tag>
      </div>
      <div class="page-header-actions">
        <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
        <el-button v-if="script.type === 'FORM'" type="primary" :icon="EditPen" @click="goEdit">
          编辑脚本
        </el-button>
        <el-button v-else type="primary" :icon="Plus" @click="openAddVersion">新增版本</el-button>
      </div>
    </div>

    <!-- 基本信息卡 -->
    <div class="page-card">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="名称">{{ script.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="最新版本">v{{ script.latestVersion ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ script.createBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ script.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(script.createTime) }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 表单摘要卡（仅 FORM 类型展示） -->
    <div v-if="script.type === 'FORM' && formDef" class="page-card">
      <div class="summary-head">
        <div>
          <div class="card-title">表单场景定义</div>
          <div class="card-desc">全局配置 + 线程组与接口编排（在编辑页修改，保存后生成新版本）</div>
        </div>
        <el-button link type="primary" :icon="EditPen" @click="goEdit">去编辑</el-button>
      </div>

      <!-- 全局配置摘要：目标环境 + 自定义变量 -->
      <el-descriptions :column="2" border size="small" class="form-brief">
        <el-descriptions-item label="目标环境">
          <span v-if="globalEnvText" class="mono">{{ globalEnvText }}</span>
          <span v-else class="dim">未配置（接口使用完整地址）</span>
        </el-descriptions-item>
        <el-descriptions-item label="思考时间">{{ formDef.thinkTimeMs || 0 }} ms</el-descriptions-item>
        <el-descriptions-item label="线程组名称">{{ formDef.threadGroupName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="自定义变量">
          <span v-if="globalVariables.length" class="var-list">
            <el-tag v-for="v in globalVariables" :key="v.name" size="small" effect="plain" class="var-tag mono">
              {{ v.name }}={{ v.value }}
            </el-tag>
          </span>
          <span v-else class="dim">无</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-table :data="flattenRows" size="small">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column label="所属分组" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="group-cell">
              {{ row.groupName }}
              <el-tag size="small" :type="row.execution === 'PARALLEL' ? 'warning' : 'info'" effect="plain">
                {{ row.execution === 'PARALLEL' ? '组内并行' : '组内串行' }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="方法" width="90" align="center">
          <template #default="{ row }">
            <span class="m-chip" :class="methodClass(row.method)">{{ row.method }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="url" label="路径 / URL" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.url }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提取" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="(row.extractors || []).length" size="small" effect="plain" type="success">
              {{ (row.extractors || []).length }}
            </el-tag>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column label="断言" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="(row.assertions || []).length" size="small" effect="plain" type="warning">
              {{ (row.assertions || []).length }}
            </el-tag>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column label="参数文件" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="(row.csvRefs || []).length" size="small" effect="plain" type="info">
              {{ (row.csvRefs || []).length }}
            </el-tag>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 版本列表 -->
    <div class="page-card">
      <div class="summary-head">
        <div class="card-title">版本历史</div>
      </div>
      <el-table :data="versions" size="small">
        <el-table-column label="版本" width="90" align="center">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="关联文件" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="fileNames(row.fileIds).length">
              <el-tag
                v-for="name in fileNames(row.fileIds)"
                :key="name"
                size="small"
                effect="plain"
                class="file-tag"
              >
                {{ name }}
              </el-tag>
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="创建人" width="110" align="center">
          <template #default="{ row }">{{ row.createBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openViewJmx(row)">查看 JMX</el-button>
            <el-button link type="success" @click="handleDownloadJmx(row)">下载 JMX</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 查看JMX 对话框 -->
    <el-dialog v-model="viewDialog.visible" title="查看 JMX" width="860px" top="5vh">
      <div v-loading="viewDialog.loading" class="jmx-viewer">
        <pre>{{ viewDialog.content }}</pre>
      </div>
      <template #footer>
        <el-button @click="viewDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增版本对话框（仅 IMPORTED 脚本：编辑 JMX 文本） -->
    <el-dialog
      v-model="addDialog.visible"
      title="新增版本"
      width="860px"
      top="5vh"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="JMX 内容" required>
          <div v-loading="addDialog.loading" class="w-full">
            <el-input
              v-model="addDialog.jmxContent"
              type="textarea"
              :rows="16"
              class="mono"
              placeholder="JMX 脚本 XML 内容（已填充当前最新版本，可直接修改）"
            />
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addDialog.remark" placeholder="版本备注（可选）" maxlength="100" clearable />
        </el-form-item>
        <el-form-item label="关联文件">
          <el-select
            v-model="addDialog.fileIds"
            placeholder="选择本版本依赖的参数/数据文件（可选）"
            multiple
            clearable
            filterable
            class="w-full"
          >
            <el-option
              v-for="item in fileOptions"
              :key="item.id"
              :label="`${item.name}（${item.fileType}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="addDialog.submitting" @click="submitAddVersion">
          提交新版本
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, EditPen, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  detail as getScriptDetail,
  downloadJmx,
  fetchJmxContent,
  addVersion
} from '@/api/script'
import { page as pageFiles } from '@/api/file'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()

/** 脚本详情数据（ScriptDetailVO，含 formDef 与 versions） */
const script = ref({})

/** 页面整体加载状态 */
const loading = ref(false)

/** 文件库下拉选项（用于 fileIds → 文件名映射与新增版本多选） */
const fileOptions = ref([])

/** 查看JMX 对话框状态 */
const viewDialog = reactive({
  visible: false,
  loading: false,
  content: ''
})

/** 新增版本对话框状态（仅 IMPORTED 脚本使用 JMX 文本编辑） */
const addDialog = reactive({
  visible: false,
  loading: false,
  submitting: false,
  jmxContent: '',
  remark: '',
  fileIds: []
})

/** 当前脚本的 formDef（仅 FORM 类型存在） */
const formDef = computed(() => script.value.formDef || null)

/** 全局目标环境预览（协议://域名[:端口]，未配置返回空串） */
const globalEnvText = computed(() => {
  const config = formDef.value?.config
  if (!config || !config.host) {
    return ''
  }
  const port = config.port ? ':' + config.port : ''
  return `${config.protocol || 'http'}://${config.host}${port}`
})

/** 全局自定义变量列表（过滤空名行） */
const globalVariables = computed(() =>
  (formDef.value?.config?.variables || []).filter((v) => v?.name)
)

/**
 * 表单摘要行：按分组顺序平铺采样器并附带分组名与执行方式，
 * 兼容旧数据无 groups 结构（直接使用顶层 samplers）
 */
const flattenRows = computed(() => {
  const def = script.value.formDef
  if (!def) {
    return []
  }
  if (Array.isArray(def.groups) && def.groups.length) {
    return def.groups.flatMap((group, gi) =>
      (group.samplers || []).map((sampler) => ({
        groupName: group.name || `分组${gi + 1}`,
        execution: group.execution === 'PARALLEL' ? 'PARALLEL' : 'SERIAL',
        ...sampler
      }))
    )
  }
  return def.samplers || []
})

/** 当前脚本的版本列表 */
const versions = computed(() => script.value.versions || [])

/**
 * 方法徽标样式类（GET 绿 / POST 蓝 / PUT 橙 / DELETE 红）
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

/**
 * 解析 fileIds 字符串（形如 "1,2"）为文件名数组：能映射到文件名则显示名称，否则显示占位
 * @param {string} fileIds 逗号分隔的文件 ID 字符串
 * @returns {Array<string>} 文件名列表
 */
function fileNames(fileIds) {
  if (!fileIds) {
    return []
  }
  return String(fileIds)
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((id) => Number.isFinite(id) && id > 0)
    .map((id) => {
      const file = fileOptions.value.find((item) => Number(item.id) === id)
      return file ? file.name : `文件 #${id}`
    })
}

/**
 * 加载脚本详情与文件下拉选项（并行请求）
 */
async function load() {
  loading.value = true
  try {
    const [detailRes, fileRes] = await Promise.all([
      getScriptDetail(route.params.id),
      pageFiles({ page: 1, size: 100 })
    ])
    script.value = detailRes.data || {}
    fileOptions.value = fileRes.data?.records || []
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 返回脚本列表页
 */
function goBack() {
  router.push('/scripts')
}

/**
 * 跳转全屏脚本编辑页（保存后生成新版本）
 */
function goEdit() {
  router.push(`/scripts/${route.params.id}/edit`)
}

/**
 * 打开查看JMX 对话框：拉取指定版本 JMX 文本只读展示
 * @param {Object} row 版本行数据
 */
async function openViewJmx(row) {
  viewDialog.visible = true
  viewDialog.loading = true
  viewDialog.content = ''
  try {
    viewDialog.content = await fetchJmxContent(route.params.id, row.version)
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    viewDialog.loading = false
  }
}

/**
 * 下载指定版本 JMX 文件
 * @param {Object} row 版本行数据
 */
async function handleDownloadJmx(row) {
  try {
    await downloadJmx(route.params.id, row.version)
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 打开新增版本对话框（仅 IMPORTED）：预填最新版本关联文件与 JMX 文本
 */
async function openAddVersion() {
  Object.assign(addDialog, {
    visible: true,
    loading: false,
    submitting: false,
    jmxContent: '',
    remark: '',
    fileIds: []
  })
  // 预填当前最新版本已关联的文件
  const latest = versions.value.reduce(
    (a, b) => (Number(b.version) > Number(a?.version ?? -1) ? b : a),
    null
  )
  if (latest && latest.fileIds) {
    addDialog.fileIds = String(latest.fileIds)
      .split(',')
      .map((part) => Number(part.trim()))
      .filter((id) => Number.isFinite(id) && id > 0)
  }
  // 导入脚本：拉取最新版本 JMX 文本填充
  addDialog.loading = true
  try {
    addDialog.jmxContent = latest
      ? await fetchJmxContent(route.params.id, latest.version)
      : ''
  } catch {
    // 拉取失败时保留空内容，用户可手动粘贴；错误提示已由拦截器统一弹出
  } finally {
    addDialog.loading = false
  }
}

/**
 * 提交新增版本（仅 IMPORTED）：校验 JMX 内容非空后提交，成功后关闭并刷新详情
 */
async function submitAddVersion() {
  if (!addDialog.jmxContent.trim()) {
    ElMessage.warning('JMX 内容不能为空')
    return
  }
  addDialog.submitting = true
  try {
    await addVersion(route.params.id, {
      jmxContent: addDialog.jmxContent,
      remark: addDialog.remark.trim(),
      fileIds: addDialog.fileIds.length ? addDialog.fileIds.join(',') : ''
    })
    addDialog.visible = false
    ElMessage.success('新版本已创建')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    addDialog.submitting = false
  }
}

// 页面挂载后自动加载脚本详情
onMounted(load)
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.page-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.page-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--pp-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.form-brief {
  margin-bottom: 14px;
}

.var-list {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}
.var-tag {
  margin: 0;
}

/* 方法徽标（与编辑页一致） */
.m-chip {
  font-family: var(--pp-font-mono);
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 5px;
}
.m-get { background-color: var(--el-color-success-light-9); color: var(--pp-success); }
.m-post { background-color: var(--el-color-primary-light-9); color: var(--pp-primary); }
.m-put { background-color: var(--el-color-warning-light-9); color: var(--pp-warning); }
.m-delete { background-color: var(--el-color-danger-light-9); color: var(--pp-danger); }
.m-other { background-color: var(--pp-bg); color: var(--pp-text-secondary); }

/* 表单摘要表格：分组名与执行方式标签同行展示 */
.group-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dim {
  color: var(--pp-text-placeholder);
}

.file-tag {
  margin-right: 6px;
  margin-bottom: 2px;
}

.w-full {
  width: 100%;
}

.jmx-viewer {
  max-height: 60vh;
  overflow: auto;
  background: var(--pp-bg);
  border: 1px solid var(--pp-border);
  border-radius: 8px;
  padding: 12px;
}

.jmx-viewer pre {
  margin: 0;
  font-family: var(--pp-font-mono);
  font-size: 12px;
  line-height: 1.6;
  color: var(--pp-text-primary);
  white-space: pre-wrap;
  word-break: break-all;
}

.mono :deep(textarea) {
  font-family: var(--pp-font-mono);
  font-size: 12px;
}
</style>
