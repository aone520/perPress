/**
 * 用户管理相关接口封装：分页查询、新建用户、更新用户（仅 ADMIN）
 */
import http from './http'

/**
 * 分页查询用户列表
 * @param {{page:number,size:number,keyword?:string}} params 查询参数
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page(params) {
  return http.get('/api/users', { params })
}

/**
 * 新建用户
 * @param {{username:string,password:string,nickname?:string,role:string}} data 用户信息
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function createUser(data) {
  return http.post('/api/users', data)
}

/**
 * 更新用户信息（nickname/role/status，password 可选用于重置密码）
 * @param {number|string} id 用户 ID
 * @param {{nickname?:string,role?:string,status?:string,password?:string}} data 更新字段
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function updateUser(id, data) {
  return http.put(`/api/users/${id}`, data)
}
