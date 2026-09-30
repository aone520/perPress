/**
 * 审计日志相关接口封装：分页查询（仅 ADMIN）
 */
import http from './http'

/**
 * 分页查询审计日志
 * @param {{page:number,size:number,keyword?:string}} params 查询参数
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page(params) {
  return http.get('/api/audit-logs', { params })
}
