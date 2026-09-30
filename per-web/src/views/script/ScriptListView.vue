<!--
  脚本中心列表页：
  - 关键字搜索 + 分页查询脚本列表（名称链接跳详情、类型/版本/描述/创建时间、复制/删除二次确认）
  - 「导入 JMX」对话框：拖拽上传 .jmx 文件 + 名称/描述/关联参数文件，multipart 提交
  - 「表单创建」跳转全屏编辑页 /scripts/new（独立编辑页承载表单定义编辑）
  - 「文件库」按钮跳转 /files 管理参数文件
-->
<template>
  <div class="page">
    <!-- 搜索工具栏 -->
    <div class="page-card toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="query.keyword"
            placeholder="搜索脚本名称"
            clearable
            :prefix-icon="Search"
            class="w-240"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
        </div>
        <div class="toolbar-right">
          <el-button type="primary" :icon="Upload" @click="openImportDialog">导入 JMX</el-button>
          <el-button :icon="EditPen" @click="router.push('/scripts/new')">表单创建</el-button>
          <el-button :icon="FolderOpened" @click="router.push('/files')">文件库</el-button>
        </div>
      </div>
    </div>

    <!-- 脚本列表 -->
    <div class="page-card">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="goDetail(row)">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="row.type === 'FORM' ? 'warning' : 'primary'" effect="plain" size="small">
              {{ row.type === 'FORM' ? '表单' : '导入' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最新版本" width="96" align="center">
          <template #default="{ row }">
            <span class="mono">v{{ row.latestVersion ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <el-button v-if="row.type === 'FORM'" link type="primary" @click="goEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
            <el-button link type="primary" @click="handleCopy(row)">复制</el-button>
            <el-button link type="danger" @click="handleRemove(row)">删除</el-button>
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

    <!-- 导入 JMX 对话框 -->
    <el-dialog
      v-model="importDialog.visible"
      title="导入 JMX 脚本"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="JMX 文件" required>
          <el-upload
            drag
            action="#"
            accept=".jmx"
            :limit="1"
            :auto-upload="false"
            :on-change="handleImportFileChange"
            :on-remove="() => (importDialog.file = null)"
            :on-exceed="() => ElMessage.warning('只能上传 1 个 JMX 文件，请先移除已选文件')"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将 .jmx 文件拖到此处，或<em>点击选择</em></div>
          </el-upload>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="importDialog.name" placeholder="请输入脚本名称" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="importDialog.description"
            type="textarea"
            :rows="2"
            placeholder="脚本描述（可选）"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="关联文件">
          <el-select
            v-model="importDialog.fileIds"
            placeholder="选择脚本依赖的参数/数据文件（可选）"
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
        <el-button @click="importDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="importDialog.submitting" @click="submitImport">
          导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Upload, EditPen, FolderOpened, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { page as pageScripts, importScript, removeScript, copyScript } from '@/api/script'
import { page as pageFiles } from '@/api/file'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

/** 搜索条件 */
const query = reactive({
  keyword: ''
})

/** 分页状态：当前页 / 每页条数 / 总记录数 */
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 列表数据与加载状态 */
const rows = ref([])
const loading = ref(false)

/** 文件库下拉选项（关联文件多选使用） */
const fileOptions = ref([])

/** 导入 JMX 对话框状态 */
const importDialog = reactive({
  visible: false,
  submitting: false,
  file: null,
  name: '',
  description: '',
  fileIds: []
})

/**
 * 加载脚本列表：根据搜索条件与分页参数请求后端
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (query.keyword && query.keyword.trim()) {
      params.keyword = query.keyword.trim()
    }
    const res = await pageScripts(params)
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
 * 加载文件库下拉选项（拉取前 100 条用于关联文件选择）
 */
async function loadFileOptions() {
  try {
    const res = await pageFiles({ page: 1, size: 100 })
    fileOptions.value = res.data?.records || []
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 跳转脚本详情页
 * @param {Object} row 脚本行数据
 */
function goDetail(row) {
  router.push(`/scripts/${row.id}`)
}

/**
 * 跳转全屏脚本编辑页（保存后生成新版本）
 * @param {Object} row 脚本行数据
 */
function goEdit(row) {
  router.push(`/scripts/${row.id}/edit`)
}

/**
 * 一键复制脚本：直接调用复制接口（连同全部版本），成功后提示新脚本名并回到第一页刷新
 * @param {Object} row 脚本行数据
 */
async function handleCopy(row) {
  try {
    const res = await copyScript(row.id)
    ElMessage.success(`已复制为「${res.data?.name || row.name + '-副本'}」`)
    page.value = 1
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 删除脚本：二次确认后调用删除接口，成功后刷新列表
 * @param {Object} row 脚本行数据
 */
async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除脚本「${row.name}」吗？其全部版本与 JMX 内容将一并删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await removeScript(row.id)
    ElMessage.success('脚本已删除')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 打开导入 JMX 对话框：重置表单并加载文件下拉选项
 */
function openImportDialog() {
  Object.assign(importDialog, {
    visible: true,
    submitting: false,
    file: null,
    name: '',
    description: '',
    fileIds: []
  })
  loadFileOptions()
}

/**
 * 导入对话框上传文件变化回调：手动暂存原生 File 对象
 * @param {Object} uploadFile el-upload 文件对象
 */
function handleImportFileChange(uploadFile) {
  importDialog.file = uploadFile.raw || null
}

/**
 * 提交导入 JMX：校验文件与名称后组装 multipart FormData 调用导入接口
 */
async function submitImport() {
  if (!importDialog.file) {
    ElMessage.warning('请选择要导入的 .jmx 文件')
    return
  }
  if (!importDialog.name.trim()) {
    ElMessage.warning('请输入脚本名称')
    return
  }
  const formData = new FormData()
  formData.append('jmxFile', importDialog.file)
  formData.append('name', importDialog.name.trim())
  formData.append('description', importDialog.description.trim())
  if (importDialog.fileIds.length) {
    formData.append('fileIds', importDialog.fileIds.join(','))
  }
  importDialog.submitting = true
  try {
    await importScript(formData)
    importDialog.visible = false
    ElMessage.success('JMX 脚本导入成功')
    page.value = 1
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    importDialog.submitting = false
  }
}

// 页面挂载后自动加载脚本列表
onMounted(load)
</script>

<style scoped>
.toolbar-card {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.w-240 {
  width: 240px;
}

.w-full {
  width: 100%;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
