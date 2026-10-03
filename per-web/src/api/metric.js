/**
 * 压测指标与报告相关接口封装：实时指标查询、压测报告查询
 */
import http from './http'

/**
 * 查询任务实时指标（时间序列 + 事务聚合 + 累计值）
 * @param {number|string} id 任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 {series,samplers,total}
 */
export function getTaskMetrics(id, params = {}) {
  return http.get(`/api/tasks/${id}/metrics`, { params })
}

/**
 * 查询任务压测报告（报告头 / 全局 KPI / 分位数 / 事务 / 节点 / 错误 / 时间序列）
 * @param {number|string} id 任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 {finalized,summary,samplers,nodes,errors,series}
 */
export function getTaskReport(id) {
  return http.get(`/api/tasks/${id}/report`)
}

/**
 * 导出任务压测报告（以 blob 方式请求，返回 HTML 报告文件流；错误时可能返回 JSON blob，由调用方解析提示）
 * @param {number|string} id 任务 ID
 * @returns {Promise<Blob>} 报告文件 blob
 */
export function exportReport(id) {
  return http.get(`/api/tasks/${id}/report/export`, { responseType: 'blob' })
}
