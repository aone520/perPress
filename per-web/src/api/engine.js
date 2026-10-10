/**
 * 压测引擎包管理接口封装：上传、版本列表与发布
 */
import http, { API_TIMEOUT } from './http'

/**
 * 上传引擎包（超时使用全局配置 API_TIMEOUT.ENGINE_UPLOAD = 1 小时）
 * @param {FormData} formData 字段：file（zip File）、version、remark
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 EnginePackageVO
 */
export function upload(formData) {
  return http.post('/api/engines', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: API_TIMEOUT.ENGINE_UPLOAD
  })
}

/**
 * 查询引擎包版本列表
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page() {
  return http.get('/api/engines')
}

/**
 * 将指定引擎包设为当前发布版本（Agent 会自动下载部署）
 * @param {number|string} id 引擎包 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function publish(id) {
  return http.post(`/api/engines/${id}/publish`)
}
