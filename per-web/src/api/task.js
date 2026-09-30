/**
 * 压测任务相关接口封装：创建、更新、分页查询、详情、启动与停止
 */
import http from './http'

/**
 * 创建压测任务
 * @param {{name:string,scriptId:number,version:number,mode:string,config:Object,nodeKeys:string[],fileDispatch:Array}} body 任务参数
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 TaskDetailVO
 */
export function create(body) {
  return http.post('/api/tasks', body)
}

/**
 * 更新压测任务（仅 CREATED 状态可编辑；脚本与版本不可修改）
 * @param {number|string} id 任务 ID
 * @param {{name:string,scriptId:number,version:number,mode:string,config:Object,nodeKeys:string[],fileDispatch:Array}} body 任务参数
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 TaskDetailVO
 */
export function updateTask(id, body) {
  return http.put(`/api/tasks/${id}`, body)
}

/**
 * 分页查询任务列表
 * @param {{page:number,size:number,keyword?:string,status?:string}} params 查询参数
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page(params) {
  return http.get('/api/tasks', { params })
}

/**
 * 查询任务详情（含各节点执行状态）
 * @param {number|string} id 任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 TaskDetailVO
 */
export function detail(id) {
  return http.get(`/api/tasks/${id}`)
}

/**
 * 启动任务
 * @param {number|string} id 任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function startTask(id) {
  return http.post(`/api/tasks/${id}/start`)
}

/**
 * 停止任务
 * @param {number|string} id 任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function stopTask(id) {
  return http.post(`/api/tasks/${id}/stop`)
}

/**
 * 删除任务（仅已创建/已完成/失败状态，连同报告与指标数据一并清理）
 * @param {number|string} id 任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function removeTask(id) {
  return http.delete(`/api/tasks/${id}`)
}

/**
 * 一键复制任务（脚本/模式/配置/节点/文件分发全部继承，新建为 CREATED，名称自动加 -副本）
 * @param {number|string} id 源任务 ID
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为新任务详情
 */
export function copyTask(id) {
  return http.post(`/api/tasks/${id}/copy`)
}
