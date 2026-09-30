/**
 * 脚本中心相关接口封装：分页查询、导入 JMX、表单新建、详情、版本管理与 JMX 下载/查看
 */
import http from './http'

/**
 * 分页查询脚本列表
 * @param {{page:number,size:number,keyword?:string}} params 查询参数
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page(params) {
  return http.get('/api/scripts', { params })
}

/**
 * 导入 JMX 文件创建脚本
 * @param {FormData} formData 字段：jmxFile（File）、name、description、fileIds（可多次 append）
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 ScriptVO
 */
export function importScript(formData) {
  return http.post('/api/scripts/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 表单方式新建脚本（服务端按 formDef 生成 JMX）
 * @param {{name:string,description:string,formDef:Object}} body 请求体
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 ScriptVO
 */
export function createForm(body) {
  return http.post('/api/scripts/form', body)
}

/**
 * 查询脚本详情（含 formDef 与版本列表）
 * @param {number|string} id 脚本 ID
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 ScriptDetailVO
 */
export function detail(id) {
  return http.get(`/api/scripts/${id}`)
}

/**
 * 拼接指定脚本版本的 JMX 接口地址（该地址需带 token 访问，请配合 downloadJmx / fetchJmxContent 使用）
 * @param {number|string} id 脚本 ID
 * @param {number|string} version 版本号
 * @returns {string} JMX 接口地址
 */
export function getJmxUrl(id, version) {
  return `/api/scripts/${id}/versions/${version}/jmx`
}

/**
 * 从拦截器返回结果中提取 Blob（blob 响应无 {code,message,data} 结构，会被拦截器原样透传）
 * @param {Blob|{data:Blob}} res axios 拦截后的返回值
 * @returns {Blob|null} 提取到的 Blob，无法识别时返回 null
 */
function pickBlob(res) {
  if (res instanceof Blob) {
    return res
  }
  if (res && res.data instanceof Blob) {
    return res.data
  }
  return null
}

/**
 * 校验 blob 是否为后端 JSON 错误响应：是则抛出携带后端 message 的异常
 * @param {Blob} blob 待校验的 blob
 */
async function assertNotErrorBlob(blob) {
  if (blob && blob.type && blob.type.includes('json')) {
    const text = await blob.text()
    let message = '请求失败'
    try {
      message = JSON.parse(text)?.message || message
    } catch {
      // 非 JSON 文本则使用默认提示
    }
    throw new Error(message)
  }
}

/**
 * 下载指定版本的 JMX 文件：以 blob 方式请求（自动带 token）并触发浏览器保存
 * @param {number|string} id 脚本 ID
 * @param {number|string} version 版本号
 */
export async function downloadJmx(id, version) {
  const res = await http.get(getJmxUrl(id, version), { responseType: 'blob' })
  const blob = pickBlob(res)
  if (!blob) {
    return
  }
  await assertNotErrorBlob(blob)
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${id}-v${version}.jmx`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * 获取指定版本 JMX 的文本内容（用于页面内查看）
 * @param {number|string} id 脚本 ID
 * @param {number|string} version 版本号
 * @returns {Promise<string>} JMX 文本内容
 */
export async function fetchJmxContent(id, version) {
  const res = await http.get(getJmxUrl(id, version), { responseType: 'blob' })
  const blob = pickBlob(res)
  if (!blob) {
    return ''
  }
  await assertNotErrorBlob(blob)
  return await blob.text()
}

/**
 * 为脚本新增一个版本（提交 JMX 文本，与 formDef 二选一）
 * @param {number|string} id 脚本 ID
 * @param {{jmxContent?:string, formDef?:Object, remark?:string, fileIds?:string}} body JSON 请求体（后端 @RequestBody 接收）
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 ScriptVersionVO
 */
export function addVersion(id, body) {
  return http.post(`/api/scripts/${id}/versions`, body)
}

/**
 * 删除脚本
 * @param {number|string} id 脚本 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function removeScript(id) {
  return http.delete(`/api/scripts/${id}`)
}

/**
 * 一键复制脚本（连同全部版本，新名称自动追加 -副本 后缀）
 * @param {number|string} id 源脚本 ID
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为复制出的新 ScriptVO
 */
export function copyScript(id) {
  return http.post(`/api/scripts/${id}/copy`)
}
