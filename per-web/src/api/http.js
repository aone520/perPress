/**
 * axios 实例与统一拦截器：
 * - 请求拦截：自动附加 Authorization: Bearer {token}
 * - 响应拦截：统一解析 {code,message,data} 结构，code!=0 时错误提示并 reject；
 *   HTTP 401 / 业务 code 401 时清除凭证并跳转登录页（含防重复跳转节流）
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import router from '@/router'

const http = axios.create({
  baseURL: '/',
  timeout: 60000
})

/** 上一次 401 处理时间戳，用于并发请求下的提示与跳转节流 */
let lastUnauthorizedAt = 0

/**
 * 统一处理未授权（401）：清除本地凭证并跳转登录页，3 秒内只处理一次
 */
function handleUnauthorized() {
  const now = Date.now()
  if (now - lastUnauthorizedAt < 3000) {
    return
  }
  lastUnauthorizedAt = now
  const auth = useAuthStore()
  auth.clearAuth()
  ElMessage.error('未登录或登录已过期，请重新登录')
  if (router.currentRoute.value.path !== '/login') {
    router.replace('/login')
  }
}

// 请求拦截：为每个请求附加 Bearer Token
http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// 响应拦截：统一处理业务码、401/403 及网络错误
http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && body.code !== undefined) {
      if (body.code === 0) {
        return body
      }
      if (body.code === 401) {
        handleUnauthorized()
        return Promise.reject(new Error(body.message || '未登录或登录已过期'))
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      handleUnauthorized()
    } else if (status === 403) {
      ElMessage.error('无权限执行该操作')
    } else {
      ElMessage.error(error.response?.data?.message || error.message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default http
