/**
 * 压测节点相关接口封装：分页查询、标签维护、删除、注册 Token 与安装命令
 */
import http from './http'

/**
 * 分页查询节点列表
 * @param {{page:number,size:number,keyword?:string,status?:string,label?:string}} params 查询参数
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page(params) {
  return http.get('/api/nodes', { params })
}

/**
 * 更新节点标签
 * @param {number|string} id 节点 ID
 * @param {string[]} labels 标签数组
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function updateLabels(id, labels) {
  return http.put(`/api/nodes/${id}/labels`, { labels })
}

/**
 * 删除节点
 * @param {number|string} id 节点 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function removeNode(id) {
  return http.delete(`/api/nodes/${id}`)
}

/**
 * 获取当前注册 Token
 * @returns {Promise<{code:number,message:string,data:{token:string}}>}
 */
export function getRegisterToken() {
  return http.get('/api/nodes/register-token')
}

/**
 * 重置注册 Token（仅 ADMIN）
 * @returns {Promise<{code:number,message:string,data:{token:string}}>}
 */
export function resetRegisterToken() {
  return http.post('/api/nodes/register-token/reset')
}

/**
 * 获取 Agent 一键安装命令
 * @returns {Promise<{code:number,message:string,data:{command:string}}>}
 */
export function getInstallCommand() {
  return http.get('/api/nodes/install-command')
}
