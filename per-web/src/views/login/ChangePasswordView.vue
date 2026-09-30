<!--
  修改密码页（路由 /change-password，无需登录也可访问）：
  - 居中卡片表单：旧密码 / 新密码 / 确认新密码（新密码需 >=8 位且同时包含字母与数字，两次输入一致）
  - 首次登录/被重置密码（mustChangePassword=true）场景由路由守卫强制引导至此页
  - 修改成功后：已登录（有 token）清除强制改密标记并跳 /nodes；未登录则跳 /login
  - 附「返回登录」入口（强制改密场景请先完成修改）
-->
<template>
  <div class="change-password-page">
    <el-card shadow="always" class="change-password-card">
      <div class="page-title">修改密码</div>
      <el-alert
        v-if="mustChange"
        type="warning"
        :closable="false"
        show-icon
        title="当前使用的是初始密码或被重置的密码，请先修改密码后继续使用平台"
        class="must-change-alert"
      />
      <el-form :model="form" label-position="top" @submit.prevent>
        <el-form-item>
          <el-input
            v-model="form.oldPassword"
            type="password"
            placeholder="旧密码"
            size="large"
            show-password
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.newPassword"
            type="password"
            placeholder="新密码（至少 8 位，需包含字母和数字）"
            size="large"
            show-password
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="确认新密码"
            size="large"
            show-password
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="loading"
          @click="handleSubmit"
        >
          确认修改
        </el-button>
        <div class="back-login">
          <el-link type="primary" :underline="false" @click="goLogin">返回登录</el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Lock, Key } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const authStore = useAuthStore()

/** 是否处于强制改密场景（登录响应中 mustChangePassword=true） */
const mustChange = computed(() => authStore.user?.mustChangePassword === true)

/** 修改密码表单数据 */
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

/** 提交按钮 loading 状态 */
const loading = ref(false)

/**
 * 校验表单：旧密码/新密码非空、新密码 >=8 位且同时包含字母与数字、两次输入一致
 * @returns {string} 校验失败的提示文案，空串表示通过
 */
function validateForm() {
  if (!form.oldPassword) {
    return '请输入旧密码'
  }
  if (!form.newPassword) {
    return '请输入新密码'
  }
  if (form.newPassword.length < 8 || !/[A-Za-z]/.test(form.newPassword) || !/[0-9]/.test(form.newPassword)) {
    return '新密码至少 8 位，且需同时包含字母和数字'
  }
  if (form.newPassword !== form.confirmPassword) {
    return '两次输入的新密码不一致'
  }
  return ''
}

/**
 * 提交修改密码：校验通过后调用修改接口；
 * 成功后清除本地强制改密标记（避免路由守卫再次拦截），已登录跳 /nodes，未登录跳 /login
 */
async function handleSubmit() {
  if (loading.value) {
    return
  }
  const error = validateForm()
  if (error) {
    ElMessage.warning(error)
    return
  }
  loading.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    // 已登录场景：本地清除强制改密标记（服务端已重置，无需再改）
    if (authStore.token && authStore.user) {
      authStore.setUser({ ...authStore.user, mustChangePassword: false })
    }
    ElMessage.success('密码修改成功')
    router.replace(authStore.token ? '/nodes' : '/login')
  } catch {
    // 失败提示已由 http.js 响应拦截器统一以 ElMessage.error 弹出，这里无需重复提示
  } finally {
    loading.value = false
  }
}

/**
 * 返回登录页（放弃修改时使用；强制改密场景守卫仍会将登录用户带回本页）
 */
function goLogin() {
  if (authStore.token) {
    authStore.clearAuth()
  }
  router.replace('/login')
}
</script>

<style scoped>
.change-password-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1d2939 0%, #2f4562 50%, #3a5a8c 100%);
}

.change-password-card {
  width: 400px;
  padding: 8px 12px 4px;
}

.page-title {
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 18px;
}

.must-change-alert {
  margin-bottom: 14px;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.back-login {
  text-align: center;
  margin-top: 12px;
}
</style>
