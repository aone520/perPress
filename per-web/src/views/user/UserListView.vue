<!--
  用户管理页（仅 ADMIN 可见）：
  - 用户名关键字搜索 + 分页查询
  - 新建用户（用户名/密码/昵称/角色）
  - 编辑用户（昵称/角色/可选重置密码）
  - 状态启停切换（内置 admin 账户禁用切换，防止锁死系统）
-->
<template>
  <div class="page">
    <!-- 搜索工具栏 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model="query.keyword"
            placeholder="用户名关键字"
            clearable
            class="w-220"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="handleReset">重置</el-button>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </el-card>

    <!-- 用户列表 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column label="昵称" min-width="140">
          <template #default="{ row }">{{ row.nickname || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'primary'">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status"
              :active-value="1"
              :inactive-value="0"
              :disabled="row.username === 'admin'"
              @change="(value) => handleStatusChange(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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

    <!-- 新建 / 编辑用户对话框 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'create' ? '新建用户' : '编辑用户'"
      width="480px"
    >
      <el-form :model="dialog.form" label-width="90px">
        <el-form-item label="用户名" required>
          <el-input
            v-model="dialog.form.username"
            placeholder="登录用户名"
            :disabled="dialog.mode === 'edit'"
            clearable
          />
        </el-form-item>
        <el-form-item :label="dialog.mode === 'create' ? '密码' : '重置密码'">
          <el-input
            v-model="dialog.form.password"
            type="password"
            show-password
            :placeholder="dialog.mode === 'create' ? '登录密码' : '留空则不修改密码'"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="dialog.form.nickname" placeholder="展示昵称（可选）" clearable />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="dialog.form.role" class="w-full">
            <el-option label="普通用户" value="USER" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Search, RefreshLeft, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { page as pageUsers, createUser, updateUser } from '@/api/user'
import { formatDateTime } from '@/utils/format'

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

/** 新建/编辑对话框状态（mode 区分 create 与 edit） */
const dialog = reactive({
  visible: false,
  saving: false,
  mode: 'create',
  form: {
    id: null,
    username: '',
    password: '',
    nickname: '',
    role: 'USER'
  }
})

/**
 * 加载用户列表：根据关键字与分页参数请求后端
 */
async function load() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (query.keyword && query.keyword.trim()) {
      params.keyword = query.keyword.trim()
    }
    const res = await pageUsers(params)
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
 * 状态开关切换：调用更新接口，失败时回滚展示状态
 * @param {Object} row 用户行数据
 * @param {string} value 切换后的状态值（ENABLED/DISABLED）
 */
async function handleStatusChange(row, value) {
  try {
    await updateUser(row.id, { status: value })
    row.status = value
    ElMessage.success(value === 'ENABLED' ? '已启用' : '已禁用')
  } catch {
    await load()
  }
}

/**
 * 重置对话框表单为干净的默认值
 */
function resetForm() {
  dialog.form.id = null
  dialog.form.username = ''
  dialog.form.password = ''
  dialog.form.nickname = ''
  dialog.form.role = 'USER'
}

/**
 * 打开新建用户对话框
 */
function openCreate() {
  resetForm()
  dialog.mode = 'create'
  dialog.visible = true
}

/**
 * 打开编辑用户对话框：回填昵称与角色，密码留空表示不修改
 * @param {Object} row 用户行数据
 */
function openEdit(row) {
  resetForm()
  dialog.mode = 'edit'
  dialog.form.id = row.id
  dialog.form.username = row.username
  dialog.form.nickname = row.nickname || ''
  dialog.form.role = row.role || 'USER'
  dialog.visible = true
}

/**
 * 保存用户：新建模式调用 createUser，编辑模式调用 updateUser（密码留空则不提交）
 */
async function saveUser() {
  const { id, username, password, nickname, role } = dialog.form
  if (dialog.mode === 'create') {
    if (!username.trim() || !password) {
      ElMessage.warning('请填写用户名和密码')
      return
    }
    dialog.saving = true
    try {
      await createUser({ username: username.trim(), password, nickname: nickname.trim(), role })
      ElMessage.success('用户创建成功')
      dialog.visible = false
      await load()
    } catch {
      // 错误提示已由 http.js 拦截器统一弹出
    } finally {
      dialog.saving = false
    }
    return
  }
  if (!id) {
    return
  }
  const data = { nickname: nickname.trim(), role }
  if (password) {
    data.password = password
  }
  dialog.saving = true
  try {
    await updateUser(id, data)
    ElMessage.success('用户已更新')
    dialog.visible = false
    await load()
  } catch {
    // 错误提示已由 http.js 拦截器统一弹出
  } finally {
    dialog.saving = false
  }
}

// 页面挂载后自动加载用户列表
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

.w-full {
  width: 100%;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
