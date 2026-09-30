<!--
  修改密码页（路由 /change-password，无需登录也可访问）：
  - 居中卡片表单：旧密码 / 新密码 / 确认新密码（新密码需 >=8 位且同时包含字母与数字，两次输入一致）
  - 首次登录/被重置密码（mustChangePassword=true）场景由路由守卫强制引导至此页
  - 修改成功后：已登录（有 token）清除强制改密标记并跳 /nodes；未登录则跳 /login
  - 附「返回登录」入口（强制改密场景请先完成修改）
-->
<template>
  <div class="change-password-page">
    <div class="change-password-card">
      <!-- 品牌区：圆角方块 logo + 产品名 + 副标语（与登录页同款视觉） -->
      <div class="brand">
        <div class="brand-logo">P</div>
        <div class="brand-text">
          <div class="brand-name">PerPress</div>
          <div class="brand-slogan">分布式压测平台</div>
        </div>
      </div>
      <!-- 卡片标题 -->
      <div class="card-title form-title">修改密码</div>
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
    </div>
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
/* 修改密码页整页：浅色底、水平垂直居中（与登录页同款视觉） */
.change-password-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--pp-bg);
}

/* 居中卡片：白底 + 细边框 + 14px 圆角 + 柔和大投影 */
.change-password-card {
  width: 380px;
  padding: 36px 32px 32px;
  box-sizing: border-box;
  background: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-radius: 14px;
  box-shadow: 0 8px 30px rgba(16, 17, 22, 0.08);
}

/* 品牌区：圆角方块 logo 与名称/副标语横向排列 */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
}

/* 品牌方块：30px 渐变底圆角小方块，白色粗体 P */
.brand-logo {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6e79dc, #4b55a8);
  color: var(--pp-surface);
  font-size: 16px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 品牌名 */
.brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--pp-text-primary);
  line-height: 1.2;
}

/* 品牌副标语 */
.brand-slogan {
  margin-top: 2px;
  font-size: 12px;
  color: var(--pp-text-secondary);
}

/* 卡片标题（card-title 为全局类，这里仅补间距） */
.form-title {
  margin-bottom: 18px;
}

/* 强制改密提示条与表单的间距 */
.must-change-alert {
  margin-bottom: 14px;
}

/* 提交按钮占满卡宽 */
.submit-btn {
  width: 100%;
  margin-top: 4px;
}

/* 返回登录入口 */
.back-login {
  text-align: center;
  margin-top: 12px;
}
</style>
