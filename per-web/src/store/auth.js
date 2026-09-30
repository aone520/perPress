/**
 * 认证状态仓库（Pinia）：维护 token 与当前用户信息，并持久化到 localStorage
 */
import { defineStore } from 'pinia'

/** localStorage 持久化键名 */
const TOKEN_KEY = 'perpress_token'
const USER_KEY = 'perpress_user'

/**
 * 从 localStorage 安全读取用户信息（JSON 解析失败时返回 null）
 * @returns {Object|null} 用户信息对象
 */
function readUserFromStorage() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    /** 登录令牌 */
    token: localStorage.getItem(TOKEN_KEY) || '',
    /** 当前登录用户信息 */
    user: readUserFromStorage()
  }),
  getters: {
    /**
     * 是否为管理员（ADMIN 角色）
     * @returns {boolean}
     */
    isAdmin: (state) => !!state.user && state.user.role === 'ADMIN'
  },
  actions: {
    /**
     * 登录成功后保存 token 与用户信息，并写入 localStorage；
     * user 对象内含 mustChangePassword（true 表示需强制修改密码），随 user 一并持久化
     * @param {string} token 登录令牌
     * @param {Object} user 用户信息（含 mustChangePassword）
     */
    setAuth(token, user) {
      this.token = token || ''
      this.user = user || null
      localStorage.setItem(TOKEN_KEY, this.token)
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
    },
    /**
     * 更新当前用户信息并同步到 localStorage
     * @param {Object} user 用户信息
     */
    setUser(user) {
      this.user = user || null
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
    },
    /**
     * 退出登录或令牌失效时清空全部认证信息
     */
    clearAuth() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
