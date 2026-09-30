<!--
  节点管理列表页（核心页面）：
  - 支持关键字（主机名/IP）与在线状态筛选、分页查询
  - 展示节点状态、标签、CPU/内存使用率（进度条按阈值变色）、版本与心跳时间
  - 标签编辑、节点删除（管理员二次确认）
  - Agent 安装命令与注册 Token 的查看/复制（管理员可重置 Token）
-->
<template>
  <div class="page">
    <!-- 搜索工具栏 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="query.keyword"
            placeholder="主机名 / IP 模糊搜索"
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
            <el-option label="在线" value="ONLINE" />
            <el-option label="离线" value="OFFLINE" />
          </el-select>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="handleReset">重置</el-button>
        </div>
        <el-button type="primary" plain :icon="DocumentCopy" @click="openInstallDialog">
          安装命令
        </el-button>
      </div>
    </el-card>

    <!-- 节点列表 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="hostname" label="主机名" min-width="140" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="状态" width="86" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'">
              {{ row.status === 'ONLINE' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="170">
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
        <el-table-column label="CPU%" width="170">
          <template #default="{ row }">
            <el-progress
              :percentage="toPercent(row.cpuUsage)"
              :color="usageColor"
              :stroke-width="10"
              :format="percentFormat"
            />
          </template>
        </el-table-column>
        <el-table-column label="内存%" width="170">
          <template #default="{ row }">
            <el-tooltip :content="memTooltip(row)" placement="top">
              <el-progress
                :percentage="toPercent(row.memUsage)"
                :color="usageColor"
                :stroke-width="10"
                :format="percentFormat"
              />
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="Agent 版本" width="100" align="center">
          <template #default="{ row }">{{ row.agentVersion || '-' }}</template>
        </el-table-column>
        <el-table-column label="引擎版本" width="100" align="center">
          <template #default="{ row }">{{ row.engineVersion || '-' }}</template>
        </el-table-column>
        <el-table-column label="最后心跳时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.lastHeartbeatTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLabelDialog(row)">编辑标签</el-button>
            <el-button
              v-if="authStore.isAdmin"
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
    </el-card>

    <!-- 编辑标签对话框 -->
    <el-dialog v-model="labelDialog.visible" title="编辑标签" width="460px">
      <el-input
        v-model="labelDialog.text"
        placeholder="多个标签用英文逗号分隔，例如：压测,预发环境"
        clearable
      />
      <template #footer>
        <el-button @click="labelDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="labelDialog.saving" @click="saveLabels">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 安装命令对话框 -->
    <el-dialog v-model="installDialog.visible" title="Agent 安装命令" width="640px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="在目标压测机（Linux/Ubuntu）以 root 执行以下命令，将自动安装 Java 17（缺失时）、部署 Agent 并注册到平台；安装完成后节点会自动出现在节点列表"
      />
      <div v-loading="installDialog.loading" class="install-body">
        <!-- 注册 Token -->
        <div class="install-section">
          <div class="install-label">
            注册 Token
            <el-button
              v-if="authStore.isAdmin"
              size="small"
              type="warning"
              plain
              @click="handleResetToken"
            >
              重置 Token
            </el-button>
          </div>
          <div class="install-line">
            <code class="token-code">{{ installDialog.token || '-' }}</code>
            <el-button
              size="small"
              type="primary"
              plain
              :icon="DocumentCopy"
              :disabled="!installDialog.token"
              @click="copyText(installDialog.token)"
            >
              复制
            </el-button>
          </div>
        </div>
        <!-- 安装命令 -->
        <div class="install-section">
          <div class="install-label">安装命令</div>
          <div class="install-line">
            <pre class="command-pre">{{ installDialog.command || '-' }}</pre>
            <el-button
              size="small"
              type="primary"
              plain
              :icon="DocumentCopy"
              :disabled="!installDialog.command"
              @click="copyText(installDialog.command)"
            >
              复制
            </el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="installDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Search, RefreshLeft, DocumentCopy } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  page as pageNodes,
  updateLabels,
  removeNode,
  getRegisterToken,
  resetRegisterToken,
  getInstallCommand
} from '@/api/node'
import { useAuthStore } from '@/store/auth'
import { formatDateTime, formatBytes } from '@/utils/format'

const authStore = useAuthStore()

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

/** 编辑标签对话框状态 */
const labelDialog = reactive({
  visible: false,
  saving: false,
  node: null,
  text: ''
})

/** 安装命令对话框状态 */
const installDialog = reactive({
  visible: false,
  loading: false,
  token: '',
  command: ''
})

/**
 * 将任意数值安全转换为 0~100 的百分比（用于进度条展示）
 * @param {number|string} value 原始使用率数值
 * @returns {number} 0~100 之间的数值
 */
function toPercent(value) {
  const n = Number(value)
  if (!Number.isFinite(n) || n < 0) {
    return 0
  }
  return Math.min(100, Math.round(n * 10) / 10)
}

/**
 * 进度条颜色规则：<70% 绿色，70~90% 橙色，>90% 红色
 * @param {number} percentage 进度百分比
 * @returns {string} 颜色值
 */
function usageColor(percentage) {
  if (percentage > 90) {
    return '#f56c6c'
  }
  if (percentage >= 70) {
    return '#e6a23c'
  }
  return '#67c23a'
}

/**
 * 进度条文本格式化：显示百分比
 * @param {number} percentage 进度百分比
 * @returns {string} 形如 "45%" 的文本
 */
function percentFormat(percentage) {
  return `${percentage}%`
}

/**
 * 内存进度条 tooltip 内容：按总量与使用率推算已用量，格式化展示 已用/总量
 * @param {Object} row 节点行数据
 * @returns {string} tooltip 文本
 */
function memTooltip(row) {
  const totalBytes = Number(row.memTotal)
  const usage = Number(row.memUsage)
  if (!Number.isFinite(totalBytes) || totalBytes <= 0 || !Number.isFinite(usage) || usage < 0) {
    return '暂无内存数据'
  }
  const usedBytes = (totalBytes * usage) / 100
  return `已用 ${formatBytes(usedBytes)} / 总量 ${formatBytes(totalBytes)}`
}

/**
 * 加载节点列表：根据当前搜索条件与分页参数请求后端
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
    const res = await pageNodes(params)
    rows.value = res.data?.records || []
    total.value = Number(res.data?.total) || 0
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    loading.value = false
  }
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
 * 打开编辑标签对话框，并回填节点当前标签
 * @param {Object} row 节点行数据
 */
function openLabelDialog(row) {
  labelDialog.node = row
  labelDialog.text = (row.labels || []).join(',')
  labelDialog.visible = true
}

/**
 * 保存标签：按逗号拆分、去空并 trim 后提交，成功后刷新列表
 */
async function saveLabels() {
  const labels = labelDialog.text
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  labelDialog.saving = true
  try {
    await updateLabels(labelDialog.node.id, labels)
    ElMessage.success('标签已更新')
    labelDialog.visible = false
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    labelDialog.saving = false
  }
}

/**
 * 删除节点：二次确认后调用删除接口，成功后刷新列表（当前页删空时回退一页）
 * @param {Object} row 节点行数据
 */
async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除节点「${row.hostname}（${row.ip}）」吗？删除后需重新安装注册 Agent。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await removeNode(row.id)
    ElMessage.success('节点已删除')
    if (rows.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 打开安装命令对话框：并行拉取注册 Token 与安装命令
 */
async function openInstallDialog() {
  installDialog.visible = true
  installDialog.loading = true
  try {
    const [tokenRes, commandRes] = await Promise.all([getRegisterToken(), getInstallCommand()])
    // 后端 data 即字符串本体（R<String>），直接取 data
    installDialog.token = tokenRes.data || ''
    installDialog.command = commandRes.data || ''
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    installDialog.loading = false
  }
}

/**
 * 重置注册 Token（仅管理员）：二次确认后调用接口并刷新展示
 */
async function handleResetToken() {
  try {
    await ElMessageBox.confirm(
      '重置后旧 Token 将立即失效，未注册的机器需使用新 Token，确认重置？',
      '重置注册 Token',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await resetRegisterToken()
    // 后端 data 即新 token 字符串本体（R<String>）
    installDialog.token = res.data || ''
    ElMessage.success('注册 Token 已重置')
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  }
}

/**
 * 复制文本到剪贴板，成功/失败给出提示
 * @param {string} text 待复制的文本
 */
async function copyText(text) {
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

// 页面挂载后自动加载节点列表
onMounted(load)
</script>

<style scoped>
.toolbar-card {
  margin-bottom: 12px;
}

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

.label-tag {
  margin-right: 6px;
  margin-bottom: 2px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.install-body {
  margin-top: 14px;
}

.install-section {
  margin-bottom: 16px;
}

.install-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.install-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.token-code {
  flex: 1;
  padding: 8px 10px;
  background-color: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
  color: #303133;
  word-break: break-all;
}

.command-pre {
  flex: 1;
  margin: 0;
  padding: 10px;
  background-color: #1d2939;
  color: #e6eefc;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
