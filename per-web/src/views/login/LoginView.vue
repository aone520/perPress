<!--
  登录页：居中卡片表单（用户名 + 密码），支持回车提交；
  登录成功后保存凭证并跳转 query.redirect 指定页或默认 /nodes
-->
<template>
  <div class="login-page">
    <el-card shadow="always" class="login-card">
      <div class="login-title">PerPress 压测平台</div>
      <el-form :model="form" label-position="top" @submit.prevent>
        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            clearable
            @keyup.enter="handleLogin"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/** 登录表单数据 */
const form = reactive({
  username: '',
  password: ''
})

/** 登录按钮 loading 状态 */
const loading = ref(false)

/**
 * 计算登录成功后的跳转地址：优先使用 query.redirect（仅允许站内路径），否则默认 /nodes
 * @returns {string} 目标路由路径
 */
function resolveRedirect() {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/nodes'
}

/**
 * 提交登录：校验非空后调用登录接口，成功保存凭证；
 * 响应 user.mustChangePassword=true 时直跳修改密码页，否则跳转 redirect 指定页或 /nodes
 */
async function handleLogin() {
  if (loading.value) {
    return
  }
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await login({ username: form.username.trim(), password: form.password })
    authStore.setAuth(res.data.token, res.data.user)
    // 首次登录/被重置密码：强制先修改密码（setAuth 已将 mustChangePassword 一并保存在 user 内）
    if (res.data.user?.mustChangePassword === true) {
      router.replace('/change-password')
      return
    }
    router.replace(resolveRedirect())
  } catch {
    // 失败提示已由 http.js 响应拦截器统一以 ElMessage.error 弹出，这里无需重复提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1d2939 0%, #2f4562 50%, #3a5a8c 100%);
}

.login-card {
  width: 380px;
  padding: 8px 12px 4px;
}

.login-title {
  text-align: center;
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 24px;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
}
</style>
