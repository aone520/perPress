<!--
  主布局组件：左侧深色导航侧栏（el-menu 路由模式）+ 顶部标题栏（用户下拉菜单）
  + 主内容区路由出口；含监控大屏 / 报告中心等业务入口
-->
<template>
  <el-container class="layout-root">
    <!-- 左侧导航 -->
    <el-aside width="220px" class="layout-aside">
      <div class="logo">PerPress</div>
      <el-menu
        :default-active="route.path"
        router
        background-color="#1d2939"
        text-color="#9aa5b5"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/nodes">
          <el-icon><Monitor /></el-icon>
          <span>节点管理</span>
        </el-menu-item>
        <el-menu-item index="/scripts">
          <el-icon><Document /></el-icon>
          <span>脚本中心</span>
        </el-menu-item>
        <el-menu-item index="/files">
          <el-icon><FolderOpened /></el-icon>
          <span>文件库</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>任务中心</span>
        </el-menu-item>
        <el-menu-item index="/monitor">
          <el-icon><DataLine /></el-icon>
          <span>监控大屏</span>
        </el-menu-item>
        <el-menu-item index="/reports">
          <el-icon><TrendCharts /></el-icon>
          <span>报告中心</span>
        </el-menu-item>
        <el-menu-item v-if="authStore.isAdmin" index="/engines">
          <el-icon><Cpu /></el-icon>
          <span>引擎管理</span>
        </el-menu-item>
        <el-menu-item v-if="authStore.isAdmin" index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item v-if="authStore.isAdmin" index="/audit">
          <el-icon><Tickets /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="layout-body">
      <!-- 顶部标题栏 -->
      <el-header class="layout-header">
        <div class="header-title">PerPress 压测平台</div>
        <el-dropdown @command="handleCommand">
          <span class="user-entry">
            <el-icon class="user-icon"><UserFilled /></el-icon>
            {{ displayName }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePassword">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <!-- 主内容区 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Monitor,
  Document,
  List,
  DataLine,
  TrendCharts,
  User,
  Tickets,
  UserFilled,
  ArrowDown,
  FolderOpened,
  Cpu
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/** 顶部展示的用户名称：优先昵称，其次用户名 */
const displayName = computed(
  () => authStore.user?.nickname || authStore.user?.username || '未知用户'
)

/**
 * 处理顶部下拉菜单命令
 * @param {string} command 命令标识（changePassword=修改密码 / logout=退出登录）
 */
function handleCommand(command) {
  if (command === 'changePassword') {
    // 保留登录态跳转修改密码页，可修改当前登录用户自己的密码
    router.push('/change-password')
    return
  }
  if (command === 'logout') {
    authStore.clearAuth()
    router.replace('/login')
  }
}
</script>

<style scoped>
.layout-root {
  height: 100%;
}

.layout-aside {
  background-color: #1d2939;
  overflow-x: hidden;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
}

.layout-aside :deep(.el-menu) {
  border-right: none;
}

.layout-aside :deep(.el-menu-item:hover) {
  background-color: #26344a;
}

.layout-aside :deep(.el-menu-item.is-active) {
  background-color: #409eff;
  color: #fff;
}

.layout-aside :deep(.el-menu-item.is-disabled) {
  opacity: 0.45;
}

.layout-body {
  min-width: 0;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.header-title {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}

.user-entry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: #303133;
  font-size: 14px;
  outline: none;
}

.user-icon {
  font-size: 16px;
}

.layout-main {
  background-color: #f5f7fa;
  padding: 16px;
  overflow-y: auto;
}
</style>
