<!--
  文件库页面：
  - 顶部工具栏：文件类型下拉筛选（全部/CSV/TXT/JAR/BIN，优先传 fileType 给后端过滤，
    后端未支持时降级为前端过滤）+「上传文件」按钮
  - 「上传文件」对话框：单文件手动上传（multipart file 字段），成功后刷新列表
  - 文件列表：文件名/类型标签（CSV success、TXT primary、JAR warning、BIN info）/大小（formatBytes）
    /MD5 前 12 位（tooltip 显示全量）/上传人/上传时间/删除（二次确认）
  - 分页查询
-->
<template>
  <div class="page">
    <!-- 工具栏（page-card 全局卡片类：白底/细边框/圆角/淡阴影） -->
    <div class="page-card toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-select
            v-model="fileType"
            placeholder="全部类型"
            class="w-140"
            clearable
            @change="handleTypeChange"
          >
            <el-option label="全部类型" value="" />
            <el-option v-for="(meta, key) in FILE_TYPE_META" :key="key" :label="key" :value="key" />
          </el-select>
          <span class="toolbar-title">参数 / 数据文件统一管理，可在脚本导入与版本创建时关联引用</span>
        </div>
        <el-button type="primary" :icon="Upload" @click="openUploadDialog">上传文件</el-button>
      </div>
    </div>

    <!-- 文件列表 -->
    <div class="page-card">
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="name" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="FILE_TYPE_META[row.fileType]?.type || 'info'" size="small" effect="plain">
              {{ row.fileType || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatBytes(row.size) }}</template>
        </el-table-column>
        <el-table-column label="MD5" width="160" align="center">
          <template #default="{ row }">
            <el-tooltip v-if="row.md5" :content="row.md5" placement="top">
              <span class="mono md5-cell">{{ row.md5.slice(0, 12) }}…</span>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="上传人" width="120" align="center">
          <template #default="{ row }">{{ row.createBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.fileType === 'CSV' || row.fileType === 'TXT'"
              link
              type="primary"
              @click="handlePreview(row)"
            >预览</el-button>
            <el-button link type="primary" @click="handleDownload(row)">下载</el-button>
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

    <!-- 上传文件对话框 -->
    <el-dialog
      v-model="uploadDialog.visible"
      title="上传文件"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-upload
        drag
        action="#"
        :limit="1"
        :auto-upload="false"
        :on-change="handleFileChange"
        :on-remove="() => (uploadDialog.file = null)"
        :on-exceed="() => ElMessage.warning('一次只能上传 1 个文件，请先移除已选文件')"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 CSV/TXT 参数文件、JAR 依赖包、BIN 二进制文件</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="uploadDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="uploadDialog.submitting" @click="submitUpload">
          上传
        </el-button>
      </template>
    </el-dialog>

    <!-- 文件预览对话框：CSV 按分隔符渲染表格（首行为表头），TXT 以纯文本展示 -->
    <el-dialog
      v-model="previewDialog.visible"
      :title="`预览：${previewDialog.name || ''}`"
      width="860px"
      destroy-on-close
    >
      <div v-loading="previewDialog.loading" class="preview-body">
        <template v-if="!previewDialog.loading">
          <!-- CSV：探测分隔符（制表符/逗号）后渲染表格，首行作表头 -->
          <template v-if="previewDialog.fileType === 'CSV' && previewDialog.columns.length">
            <el-table :data="previewDialog.rows" size="small" border max-height="480">
              <el-table-column
                v-for="(col, idx) in previewDialog.columns"
                :key="idx"
                :prop="String(idx)"
                :label="col"
                min-width="120"
                show-overflow-tooltip
              />
            </el-table>
          </template>
          <!-- TXT：等宽字体纯文本 -->
          <pre v-else class="txt-preview">{{ previewDialog.lines.join('\n') }}</pre>
          <div v-if="previewDialog.truncated" class="preview-truncated">
            仅展示前 {{ previewDialog.lines.length }} 行，完整内容请下载查看
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="previewDialog.visible = false">关闭</el-button>
        <el-button type="primary" :disabled="previewDialog.loading" @click="handleDownload(previewDialog.row)">
          下载完整文件
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Upload, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { page as pageFiles, upload as uploadFile, removeFile, previewFile, downloadFile } from '@/api/file'
import { formatDateTime, formatBytes } from '@/utils/format'

/** 文件类型元信息：tag 颜色（CSV success / TXT primary / JAR warning / BIN info） */
const FILE_TYPE_META = {
  CSV: { type: 'success' },
  TXT: { type: 'primary' },
  JAR: { type: 'warning' },
  BIN: { type: 'info' }
}

/** 分页状态：当前页 / 每页条数 / 总记录数 */
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** 文件类型筛选：空串表示全部类型 */
const fileType = ref('')

/** 列表数据与加载状态 */
const rows = ref([])
const loading = ref(false)

/** 上传对话框状态 */
const uploadDialog = reactive({
  visible: false,
  submitting: false,
  file: null
})

/** 预览对话框状态：lines 原始行、columns/rows 为 CSV 表格化结果 */
const previewDialog = reactive({
  visible: false,
  loading: false,
  row: null,
  name: '',
  fileType: '',
  truncated: false,
  lines: [],
  columns: [],
  rows: []
})

/**
 * 打开预览：调用预览接口取前 100 行；CSV 自动探测分隔符（制表符优先于逗号），
 * 首行作表头、其余行为数据，列数以表头为准
 * @param {Object} row 文件行数据
 */
async function handlePreview(row) {
  Object.assign(previewDialog, {
    visible: true,
    loading: true,
    row,
    name: row.name,
    fileType: row.fileType,
    truncated: false,
    lines: [],
    columns: [],
    rows: []
  })
  try {
    const res = await previewFile(row.id)
    const lines = res.data?.lines || []
    previewDialog.lines = lines
    previewDialog.truncated = Boolean(res.data?.truncated)
    if (row.fileType === 'CSV' && lines.length) {
      // 分隔符探测：首行含制表符且多于逗号分隔列时用 \t，否则用逗号
      const first = lines[0]
      const tabCols = first.split('\t')
      const commaCols = first.split(',')
      const delimiter = tabCols.length > commaCols.length ? '\t' : ','
      previewDialog.columns = tabCols.length > commaCols.length ? tabCols : commaCols
      const colCount = previewDialog.columns.length
      previewDialog.rows = lines.slice(1).map((line) => {
        const cells = line.split(delimiter)
        const record = {}
        for (let i = 0; i < colCount; i++) {
          record[String(i)] = cells[i] ?? ''
        }
        return record
      })
    }
  } catch {
    previewDialog.visible = false
  } finally {
    previewDialog.loading = false
  }
}

/**
 * 下载文件：blob 拉取后触发浏览器保存，成功轻提示
 * @param {Object} row 文件行数据
 */
async function handleDownload(row) {
  if (!row) {
    return
  }
  try {
    await downloadFile(row.id, row.name)
    ElMessage.success('文件下载已开始')
  } catch (e) {
    ElMessage.error(e.message || '下载失败')
  }
}

/**
 * 加载文件列表：按分页参数请求后端；选择了类型时优先传 fileType 参数由后端过滤，
 * 若后端未支持该过滤（返回的 records 混入其他类型，total 与过滤后条数对不上），降级为前端过滤
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (fileType.value) {
      params.fileType = fileType.value
    }
    const res = await pageFiles(params)
    let records = res.data?.records || []
    if (fileType.value && records.some((row) => row.fileType !== fileType.value)) {
      // 后端未按 fileType 过滤的降级方案：仅对当页数据做前端过滤，
      // total 同步修正为当页过滤后条数（降级场景下跨页总数无法精确统计）
      records = records.filter((row) => row.fileType === fileType.value)
      total.value = records.length
    } else {
      total.value = Number(res.data?.total) || 0
    }
    rows.value = records
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 文件类型筛选变化：回到第一页并重新加载
 */
function handleTypeChange() {
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
 * 打开上传对话框：重置已选文件
 */
function openUploadDialog() {
  Object.assign(uploadDialog, {
    visible: true,
    submitting: false,
    file: null
  })
}

/**
 * 上传文件变化回调：手动暂存原生 File 对象
 * @param {Object} uploadFile el-upload 文件对象
 */
function handleFileChange(uploadFile) {
  uploadDialog.file = uploadFile.raw || null
}

/**
 * 提交上传：组装 FormData(file) 调用上传接口，成功后关闭对话框并刷新列表
 */
async function submitUpload() {
  if (!uploadDialog.file) {
    ElMessage.warning('请选择要上传的文件')
    return
  }
  const formData = new FormData()
  formData.append('file', uploadDialog.file)
  uploadDialog.submitting = true
  try {
    await uploadFile(formData)
    uploadDialog.visible = false
    ElMessage.success('文件上传成功')
    page.value = 1
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    uploadDialog.submitting = false
  }
}

/**
 * 删除文件：二次确认后调用删除接口，成功后刷新列表
 * @param {Object} row 文件行数据
 */
async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除文件「${row.name}」吗？已关联该文件的脚本版本将无法分发该文件。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await removeFile(row.id)
    ElMessage.success('文件已删除')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

// 页面挂载后自动加载文件列表
onMounted(load)
</script>

<style scoped>
/* 工具栏卡片与列表卡片间距由全局 .page-card + .page-card 统一控制（16px），此处不再重复声明 */

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

.toolbar-title {
  font-size: 13px;
  color: var(--pp-text-secondary);
}

.w-140 {
  width: 140px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

/* mono 等宽类已由全局 base.css 提供 */

.md5-cell {
  cursor: default;
}

/* 预览对话框正文最小高度（loading 态不塌陷） */
.preview-body {
  min-height: 160px;
}

/* TXT 纯文本预览：等宽字体 + 滚动 */
.txt-preview {
  margin: 0;
  max-height: 480px;
  overflow: auto;
  font-family: var(--font-mono, 'SFMono-Regular', Consolas, monospace);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 6px;
}

/* 截断提示条 */
.preview-truncated {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-color-warning);
}
</style>
