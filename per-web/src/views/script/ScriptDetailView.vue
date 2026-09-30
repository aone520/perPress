<!--
  脚本详情页（/scripts/:id）：
  - 返回按钮 + 基本信息卡（名称/类型/描述/最新版本/创建人/创建时间）
  - FORM 类型脚本展示表单摘要卡（线程组/思考时间 + 采样器表格：名称/方法/URL/断言数/参数文件引用数）
  - 版本列表：版本/备注/关联文件（映射文件名）/创建时间/操作（查看 JMX 只读弹窗、下载 JMX）
  - 「新增版本」对话框：FORM 脚本用 FormScriptEditor 表单编辑（预填当前 formDef），
    IMPORTED 脚本保持 JMX 文本编辑（拉取最新版 JMX 填充），均支持备注与关联文件多选
-->
<template>
  <div class="page" v-loading="loading">
    <!-- 返回 + 页头操作 -->
    <div class="page-head">
      <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
      <el-button type="primary" :icon="Plus" @click="openAddVersion">新增版本</el-button>
    </div>

    <!-- 基本信息卡 -->
    <el-card shadow="never" class="block-card">
      <template #header><span class="card-title">基本信息</span></template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="名称">{{ script.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="script.type === 'FORM' ? 'warning' : 'primary'" effect="plain">
            {{ script.type === 'FORM' ? '表单' : '导入' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="最新版本">v{{ script.latestVersion ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ script.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ script.createBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(script.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="更新时间" :span="2">{{ formatDateTime(script.updateTime) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 表单摘要卡（仅 FORM 类型展示） -->
    <el-card v-if="script.type === 'FORM' && formDef" shadow="never" class="block-card">
      <template #header><span class="card-title">表单场景定义</span></template>
      <el-descriptions :column="2" border size="small" class="form-brief">
        <el-descriptions-item label="线程组名称">{{ formDef.threadGroupName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="思考时间">{{ formDef.thinkTimeMs || 0 }} ms</el-descriptions-item>
      </el-descriptions>
      <el-table :data="flattenRows" border stripe size="small">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column label="所属分组" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="group-cell">
              {{ row.groupName }}
              <el-tag size="small" :type="row.execution === 'PARALLEL' ? 'danger' : 'info'" effect="plain">
                {{ row.execution === 'PARALLEL' ? '组内并行' : '组内串行' }}
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.method }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="url" label="URL" min-width="220" show-overflow-tooltip />
        <el-table-column label="断言数" width="80" align="center">
          <template #default="{ row }">{{ (row.assertions || []).length }}</template>
        </el-table-column>
        <el-table-column label="参数文件引用数" width="120" align="center">
          <template #default="{ row }">{{ (row.csvRefs || []).length }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 版本列表 -->
    <el-card shadow="never" class="block-card">
      <template #header><span class="card-title">版本历史</span></template>
      <el-table :data="versions" border stripe>
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
    </el-card>

    <!-- 查看JMX 对话框 -->
    <el-dialog v-model="viewDialog.visible" title="查看 JMX" width="860px" top="5vh">
      <div v-loading="viewDialog.loading" class="jmx-viewer">
        <pre>{{ viewDialog.content }}</pre>
      </div>
      <template #footer>
        <el-button @click="viewDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新增版本对话框：FORM 脚本走表单编辑（FormScriptEditor），IMPORTED 脚本走 JMX 文本编辑 -->
    <el-dialog
      v-model="addDialog.visible"
      title="新增版本"
      :width="script.type === 'FORM' ? '960px' : '860px'"
      top="5vh"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <!-- 表单方式：编辑表单场景定义生成新版本 -->
      <template v-if="script.type === 'FORM'">
        <div class="section-title">表单场景定义（已预填当前定义，修改后提交为新版本）</div>
        <FormScriptEditor v-model="addDialog.formDef" :file-options="fileOptions" />
        <el-form label-width="90px" class="version-extra-form">
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
      </template>

      <!-- 导入方式：编辑 JMX 文本 -->
      <el-form v-else label-width="90px">
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
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  detail as getScriptDetail,
  downloadJmx,
  fetchJmxContent,
  addVersion
} from '@/api/script'
import { page as pageFiles } from '@/api/file'
import { formatDateTime } from '@/utils/format'
import FormScriptEditor, { sanitizeFormDef, validateFormDef } from '@/components/FormScriptEditor.vue'

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

/** 新增版本对话框状态（FORM 脚本用 formDef，IMPORTED 脚本用 jmxContent） */
const addDialog = reactive({
  visible: false,
  loading: false,
  submitting: false,
  jmxContent: '',
  remark: '',
  fileIds: [],
  formDef: null
})

/** 当前脚本的 formDef（仅 FORM 类型存在） */
const formDef = computed(() => script.value.formDef || null)

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
 * 打开新增版本对话框：重置表单并预填最新版本关联文件；
 * FORM 脚本预填当前表单定义（深拷贝），IMPORTED 脚本拉取最新版本 JMX 文本填充编辑区
 */
async function openAddVersion() {
  Object.assign(addDialog, {
    visible: true,
    loading: false,
    submitting: false,
    jmxContent: '',
    remark: '',
    fileIds: [],
    formDef: null
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
  // 表单脚本：深拷贝当前 formDef 作为新版本编辑起点
  if (script.value.type === 'FORM') {
    addDialog.formDef = script.value.formDef
      ? JSON.parse(JSON.stringify(script.value.formDef))
      : {}
    return
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
 * 提交新增版本：FORM 脚本校验并清洗表单定义后提交 formDef，
 * IMPORTED 脚本校验 JMX 内容非空后提交 jmxContent，成功后关闭并刷新详情
 */
async function submitAddVersion() {
  const isForm = script.value.type === 'FORM'
  let body
  if (isForm) {
    const invalid = validateFormDef(addDialog.formDef)
    if (invalid) {
      ElMessage.warning(invalid)
      return
    }
    body = {
      formDef: sanitizeFormDef(addDialog.formDef),
      remark: addDialog.remark.trim(),
      fileIds: addDialog.fileIds.length ? addDialog.fileIds.join(',') : ''
    }
  } else {
    if (!addDialog.jmxContent.trim()) {
      ElMessage.warning('JMX 内容不能为空')
      return
    }
    body = {
      jmxContent: addDialog.jmxContent,
      remark: addDialog.remark.trim(),
      fileIds: addDialog.fileIds.length ? addDialog.fileIds.join(',') : ''
    }
  }
  addDialog.submitting = true
  try {
    await addVersion(route.params.id, body)
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
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.block-card {
  margin-bottom: 12px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.form-brief {
  margin-bottom: 12px;
}

/* 表单摘要表格：分组名与执行方式标签同行展示 */
.group-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 新增版本对话框：表单定义区标题 */
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 10px;
}

/* 新增版本对话框：表单定义下方的备注/关联文件表单 */
.version-extra-form {
  margin-top: 12px;
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
  background: #f5f7fa;
  border-radius: 4px;
  padding: 12px;
}

.jmx-viewer pre {
  margin: 0;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-all;
}

.mono :deep(textarea) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
}
</style>
