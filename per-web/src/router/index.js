/**
 * 路由配置与全局守卫：
 * - 无 token 一律重定向到 /login（/change-password 修改密码页除外）
 * - 登录用户 mustChangePassword=true 时强制重定向到 /change-password（目标为 /change-password、/login 时除外）
 * - meta.requiresAdmin 页面在非 ADMIN 时重定向到 /nodes
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import MainLayout from '@/layout/MainLayout.vue'
import LoginView from '@/views/login/LoginView.vue'
import ChangePasswordView from '@/views/login/ChangePasswordView.vue'
import NodeListView from '@/views/node/NodeListView.vue'
import UserListView from '@/views/user/UserListView.vue'
import AuditLogView from '@/views/audit/AuditLogView.vue'
import ScriptListView from '@/views/script/ScriptListView.vue'
import ScriptDetailView from '@/views/script/ScriptDetailView.vue'
import FileListView from '@/views/file/FileListView.vue'
import EngineListView from '@/views/engine/EngineListView.vue'
import TaskListView from '@/views/task/TaskListView.vue'
import TaskDetailView from '@/views/task/TaskDetailView.vue'

/** 路由表：登录页 + 主布局下的业务页面 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { title: '登录' }
  },
  {
    // 修改密码页：无需登录也可访问（支持未登录直达，已登录可修改自己的密码）
    path: '/change-password',
    name: 'ChangePassword',
    component: ChangePasswordView,
    meta: { title: '修改密码' }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/nodes',
    children: [
      {
        path: 'nodes',
        name: 'NodeList',
        component: NodeListView,
        meta: { title: '节点管理' }
      },
      {
        path: 'scripts',
        name: 'Scripts',
        component: ScriptListView,
        meta: { title: '脚本中心' }
      },
      {
        path: 'scripts/:id',
        name: 'ScriptDetail',
        component: ScriptDetailView,
        meta: { title: '脚本详情' }
      },
      {
        path: 'files',
        name: 'Files',
        component: FileListView,
        meta: { title: '文件库' }
      },
      {
        path: 'tasks',
        name: 'Tasks',
        component: TaskListView,
        meta: { title: '任务中心' }
      },
      {
        path: 'tasks/:id',
        name: 'TaskDetail',
        component: TaskDetailView,
        meta: { title: '任务详情' }
      },
      {
        path: 'engines',
        name: 'Engines',
        component: EngineListView,
        meta: { title: '引擎管理', requiresAdmin: true }
      },
      {
        path: 'monitor',
        name: 'Monitor',
        component: () => import('@/views/monitor/MonitorView.vue'),
        meta: { title: '监控大屏' }
      },
      {
        path: 'reports',
        name: 'Reports',
        component: () => import('@/views/report/ReportListView.vue'),
        meta: { title: '报告中心' }
      },
      {
        path: 'reports/:id',
        name: 'ReportDetail',
        component: () => import('@/views/report/ReportDetailView.vue'),
        meta: { title: '压测报告' }
      },
      {
        path: 'users',
        name: 'UserList',
        component: UserListView,
        meta: { title: '用户管理', requiresAdmin: true }
      },
      {
        path: 'audit',
        name: 'Audit',
        component: AuditLogView,
        meta: { title: '审计日志', requiresAdmin: true }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 免登录即可访问的路径白名单：登录页与修改密码页 */
const PUBLIC_PATHS = ['/login', '/change-password']

/**
 * 全局前置守卫：登录态校验、强制改密校验与管理员权限校验
 * @param {object} to 目标路由
 * @returns {boolean|object} 放行或重定向目标
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  // 未登录：除白名单（登录/修改密码）外一律跳转登录页，并记录回跳地址
  if (!auth.token && !PUBLIC_PATHS.includes(to.path)) {
    return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  // 已登录访问登录页：直接进入首页
  if (auth.token && to.path === '/login') {
    return { path: '/' }
  }
  // 强制改密：登录用户 mustChangePassword=true 时，除 /change-password、/login 外一律重定向到修改密码页
  if (
    auth.token &&
    auth.user?.mustChangePassword === true &&
    !PUBLIC_PATHS.includes(to.path)
  ) {
    return { path: '/change-password' }
  }
  // 非管理员访问管理员页面：重定向到节点管理
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { path: '/nodes' }
  }
  return true
})

export default router
