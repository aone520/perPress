/**
 * 认证相关接口封装：登录、修改密码、获取当前登录用户
 */
import http from './http'

/**
 * 用户登录
 * @param {{username:string,password:string}} data 登录表单
 * @returns {Promise<{code:number,message:string,data:{token:string,user:Object}}>}
 *          data.user 内含 mustChangePassword（true 表示首次登录/被重置密码，需强制修改）
 */
export function login(data) {
  return http.post('/api/auth/login', data)
}

/**
 * 修改当前用户密码（旧密码校验通过后生效）
 * @param {{oldPassword:string,newPassword:string}} data 旧密码与新密码
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function changePassword(data) {
  return http.post('/api/auth/change-password', data)
}

/**
 * 获取当前登录用户信息
 * @returns {Promise<{code:number,message:string,data:Object}>}
 *          data 内含 mustChangePassword（true 表示仍需强制修改密码）
 */
export function me() {
  return http.get('/api/auth/me')
}
