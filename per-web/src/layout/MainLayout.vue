<!--
  主布局组件：左侧近黑蓝导航侧栏（圆角 pill 激活态 + 图标菜单，数据驱动渲染，支持折叠收起为图标栏）
  + 顶部精简标题栏（折叠开关 + 当前页面名 + 用户下拉）+ 主内容区路由出口；
  配色全部引用设计令牌（src/styles/tokens.css），不再硬编码色值
-->
<template>
  <el-container class="layout-root">
    <!-- 左侧导航（collapsed 时收起为 64px 图标栏，菜单项 hover 出 tooltip） -->
    <el-aside :width="collapsed ? '64px' : '228px'" class="layout-aside" :class="{ collapsed }">
      <div class="logo" :class="{ collapsed }">
        <div class="logo-mark">P</div>
        <div v-if="!collapsed" class="logo-text">
          <span class="logo-name">PerPress</span>
          <span class="logo-slogan">分布式压测平台</span>
        </div>
      </div>
      <el-menu :default-active="activePath" router :collapse="collapsed" :collapse-transition="false" class="side-menu">
        <el-menu-item v-for="menu in visibleMenus" :key="menu.path" :index="menu.path">
          <el-icon class="menu-icon"><component :is="menu.icon" /></el-icon>
          <template #title>{{ menu.label }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="layout-body">
      <!-- 顶部标题栏：折叠开关 + 当前页面名 + 用户入口 -->
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleCollapse">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
          <div class="header-title">{{ pageTitle }}</div>
        </div>
        <el-dropdown @command="handleCommand">
          <span class="user-entry">
            <span class="user-avatar">{{ avatarChar }}</span>
            <span class="user-name">{{ displayName }}</span>
            <el-icon class="user-caret"><ArrowDown /></el-icon>
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
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Monitor,
  Document,
  List,
  DataLine,
  TrendCharts,
  User,
  Tickets,
  ArrowDown,
  FolderOpened,
  Cpu,
  Fold,
  Expand
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/** 折叠状态 localStorage 键 */
const COLLAPSE_KEY = 'per-aside-collapsed'

/** 侧边栏折叠状态（localStorage 持久化，刷新后保持） */
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')

/**
 * 切换侧边栏折叠/展开并持久化
 */
function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
}

/** 基础菜单（数据驱动渲染，新增入口只需在此追加） */
const baseMenus = [
  { path: '/nodes', label: '节点管理', icon: Monitor },
  { path: '/scripts', label: '脚本中心', icon: Document },
  { path: '/files', label: '文件库', icon: FolderOpened },
  { path: '/tasks', label: '任务中心', icon: List },
  { path: '/monitor', label: '监控大屏', icon: DataLine },
  { path: '/reports', label: '报告中心', icon: TrendCharts }
]

/** 管理员专属菜单 */
const adminMenus = [
  { path: '/engines', label: '引擎管理', icon: Cpu },
  { path: '/users', label: '用户管理', icon: User },
  { path: '/audit', label: '审计日志', icon: Tickets }
]

/** 实际渲染的菜单：基础 + 管理员可见的管理项 */
const visibleMenus = computed(() =>
  authStore.isAdmin ? [...baseMenus, ...adminMenus] : baseMenus
)

/** 菜单激活路径：取一级路径（/scripts/1/edit → /scripts），保证详情/编辑页高亮正确 */
const activePath = computed(() => '/' + (route.path.split('/')[1] || ''))

/** 顶栏页面标题：优先路由 meta.title，其次按激活路径匹配菜单名 */
const pageTitle = computed(() => {
  if (route.meta?.title) {
    return route.meta.title
  }
  const hit = visibleMenus.value.find((menu) => menu.path === activePath.value)
  return hit ? hit.label : 'PerPress'
})

/** 顶部展示的用户名称：优先昵称，其次用户名 */
const displayName = computed(
  () => authStore.user?.nickname || authStore.user?.username || '未知用户'
)

/** 用户头像字符：展示名首字符（中文/字母均可） */
const avatarChar = computed(() => displayName.value.charAt(0).toUpperCase())

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

/* ===== 侧边栏：近黑蓝底，menu 与 aside 同色为一体，支持折叠过渡 ===== */
.layout-aside {
  background-color: var(--pp-aside-bg);
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  transition: width 0.2s ease;
}

/* logo 区：渐变小方块 + 字标 + 副标语（折叠态收起字标居中） */
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 20px 14px;
}
.logo.collapsed {
  justify-content: center;
  padding: 18px 0 14px;
}
.logo-mark {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6e79dc, #4b55a8);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}
.logo-name {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.4px;
}
.logo-slogan {
  color: var(--pp-aside-text);
  font-size: 11px;
}

/* 菜单：透明底融入侧栏，pill 圆角激活态 */
.side-menu {
  border-right: none;
  padding: 6px 10px;
  background-color: transparent;
}
.side-menu :deep(.el-menu-item) {
  height: 42px;
  line-height: 42px;
  margin-bottom: 2px;
  border-radius: 8px;
  color: var(--pp-aside-text);
  background-color: transparent;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.side-menu :deep(.el-menu-item:hover) {
  background-color: var(--pp-aside-hover);
  color: #d6d9e4;
}
.side-menu :deep(.el-menu-item.is-active) {
  background-color: var(--pp-aside-active-bg);
  color: var(--pp-aside-active-text);
}
.menu-icon {
  font-size: 17px;
  margin-right: 4px;
}

.layout-body {
  min-width: 0;
}

/* ===== 顶栏：白底细分割线，标题左置 ===== */
.layout-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: var(--pp-surface);
  border-bottom: 1px solid var(--pp-border);
  padding: 0 24px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

/* 折叠开关：与标题同排的图标按钮 */
.collapse-btn {
  font-size: 17px;
  color: var(--pp-text-secondary);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: background-color 0.15s ease, color 0.15s ease;
  flex-shrink: 0;
}
.collapse-btn:hover {
  background-color: var(--pp-bg);
  color: var(--pp-text-primary);
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--pp-text-primary);
}

/* 用户入口：圆形首字符头像 + 名称 + 下拉箭头 */
.user-entry {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  padding: 4px 6px;
  border-radius: 8px;
  transition: background-color 0.15s ease;
}
.user-entry:hover {
  background-color: var(--pp-bg);
}
.user-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background-color: var(--el-color-primary-light-8);
  color: var(--pp-primary);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 13px;
  color: var(--pp-text-regular);
}
.user-caret {
  font-size: 12px;
  color: var(--pp-text-placeholder);
}

/* ===== 主内容区 ===== */
.layout-main {
  background-color: var(--pp-bg);
  padding: 20px 24px;
  overflow-y: auto;
}
</style>
