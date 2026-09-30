<!--
  引擎管理页面（仅 ADMIN，路由守卫已控制访问）：
  - 顶部说明：上传 JMeter 引擎发行包 zip 并发布后，全部 Agent 在心跳周期内自动下载部署
  - 「上传引擎包」对话框：版本号（如 5.6.3-per1）/备注/zip 文件，multipart 提交
  - 引擎包列表（接口返回数组，按 id 倒序）：版本/文件名/大小/MD5 缩略/当前发布标记
    （isCurrent==1 时 success 标签「发布中」）/备注/时间/操作（设为当前发布，二次确认）
-->
<template>
  <div class="page">
    <!-- 说明与操作 -->
    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="tip-alert"
      title="上传 JMeter 官方发行包 zip（如 apache-jmeter-5.6.3.zip）并发布即可——平台的并发/固定TPS/阶梯三种压测模式全部基于 JMeter 官方自带元件实现，无需任何插件；发布后 Agent 将在心跳周期内自动下载部署，全节点版本一致"
    />

    <!-- 工具栏（page-card 全局卡片类：白底/细边框/圆角/淡阴影） -->
    <div class="page-card toolbar-card">
      <div class="toolbar">
        <span class="toolbar-title">引擎包列表（{{ rows.length }}）</span>
        <el-button type="primary" :icon="Upload" @click="openUploadDialog">上传引擎包</el-button>
      </div>
    </div>

    <!-- 引擎包列表 -->
    <div class="page-card">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="版本" width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
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
        <el-table-column label="当前发布" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isCurrent === 1" type="success" size="small">发布中</el-tag>
            <el-tag v-else type="info" size="small" effect="plain">未发布</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.isCurrent !== 1"
              link
              type="success"
              @click="handlePublish(row)"
            >
              设为当前发布
            </el-button>
            <span v-else class="current-text">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 上传引擎包对话框 -->
    <el-dialog
      v-model="uploadDialog.visible"
      title="上传引擎包"
      width="560px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="版本号" required>
          <el-input v-model="uploadDialog.version" placeholder="如 5.6.3-per1" maxlength="64" clearable />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="uploadDialog.remark" placeholder="发行说明（可选）" maxlength="200" clearable />
        </el-form-item>
        <el-form-item label="引擎包" required>
          <el-upload
            drag
            action="#"
            accept=".zip"
            :limit="1"
            :auto-upload="false"
            :on-change="handleFileChange"
            :on-remove="() => (uploadDialog.file = null)"
            :on-exceed="() => ElMessage.warning('一次只能上传 1 个 zip 包，请先移除已选文件')"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将引擎包 .zip 拖到此处，或<em>点击选择</em></div>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="uploadDialog.submitting" @click="submitUpload">
          上传
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Upload, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { page as pageEngines, upload as uploadEngine, publish as publishEngine } from '@/api/engine'
import { formatDateTime, formatBytes } from '@/utils/format'

/** 引擎包列表数据（接口返回数组，按 id 倒序）与加载状态 */
const rows = ref([])
const loading = ref(false)

/** 上传对话框状态 */
const uploadDialog = reactive({
  visible: false,
  submitting: false,
  version: '',
  remark: '',
  file: null
})

/**
 * 加载引擎包列表（data 为数组结构）
 */
async function load() {
  loading.value = true
  try {
    const res = await pageEngines()
    rows.value = Array.isArray(res.data) ? res.data : []
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

/**
 * 打开上传对话框：重置表单
 */
function openUploadDialog() {
  Object.assign(uploadDialog, {
    visible: true,
    submitting: false,
    version: '',
    remark: '',
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
 * 提交上传引擎包：校验版本号与文件后组装 multipart FormData 调用上传接口
 */
async function submitUpload() {
  if (!uploadDialog.version.trim()) {
    ElMessage.warning('请输入引擎版本号')
    return
  }
  if (!uploadDialog.file) {
    ElMessage.warning('请选择要上传的引擎包 zip 文件')
    return
  }
  const formData = new FormData()
  formData.append('file', uploadDialog.file)
  formData.append('version', uploadDialog.version.trim())
  formData.append('remark', uploadDialog.remark.trim())
  uploadDialog.submitting = true
  try {
    await uploadEngine(formData)
    uploadDialog.visible = false
    ElMessage.success('引擎包上传成功，已设为当前发布版本')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    uploadDialog.submitting = false
  }
}

/**
 * 设为当前发布：二次确认后调用发布接口，成功提示 Agent 自动部署并刷新列表
 * @param {Object} row 引擎包行数据
 */
async function handlePublish(row) {
  try {
    await ElMessageBox.confirm(
      `确认将引擎包「${row.version}」设为当前发布版本吗？全部 Agent 将在心跳周期内自动下载部署。`,
      '发布确认',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await publishEngine(row.id)
    ElMessage.success('Agent 将在心跳周期内自动部署')
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

// 页面挂载后自动加载引擎包列表
onMounted(load)
</script>

<style scoped>
.tip-alert {
  margin-bottom: 12px;
}

/* 工具栏卡片与列表卡片间距由全局 .page-card + .page-card 统一控制（16px），此处不再重复声明 */

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--pp-text-primary);
}

/* mono 等宽类已由全局 base.css 提供 */

.md5-cell {
  cursor: default;
}

.current-text {
  color: var(--pp-text-placeholder);
}
</style>
