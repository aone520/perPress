/**
 * 压测引擎包管理接口封装：上传、版本列表与发布
 */
import http from './http'

/** 引擎包上传专用超时：zip 体积大 + 服务端校验耗时，单独放宽到 1 小时（其他接口沿用全局 60s） */
const ENGINE_UPLOAD_TIMEOUT_MS = 60 * 60 * 1000

/**
 * 上传引擎包
 * @param {FormData} formData 字段：file（zip File）、version、remark
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 EnginePackageVO
 */
export function upload(formData) {
  return http.post('/api/engines', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: ENGINE_UPLOAD_TIMEOUT_MS
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
