/**
 * 文件库相关接口封装：文件上传、分页查询、删除、预览与下载
 */
import http from './http'

/**
 * 上传单个文件
 * @param {FormData} formData 含 file 字段的 multipart 表单数据
 * @returns {Promise<{code:number,message:string,data:Object}>} data 为 FileVO
 */
export function upload(formData) {
  return http.post('/api/files', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 分页查询文件列表
 * @param {{page:number,size:number}} params 分页参数
 * @returns {Promise<{code:number,message:string,data:{records:Array,total:number}}>}
 */
export function page(params) {
  return http.get('/api/files', { params })
}

/**
 * 删除文件
 * @param {number|string} id 文件 ID
 * @returns {Promise<{code:number,message:string,data:Object}>}
 */
export function removeFile(id) {
  return http.delete(`/api/files/${id}`)
}

/**
 * 文本文件预览（仅 CSV/TXT）：返回前 100 行内容
 * @param {number|string} id 文件 ID
 * @returns {Promise<{code:number,message:string,data:{id:number,name:string,fileType:string,size:number,lines:string[],truncated:boolean}}>}
 */
export function previewFile(id) {
  return http.get(`/api/files/${id}/preview`)
}

/**
 * 下载文件：以 blob 拉取（携带鉴权头）后创建本地下载链接
 * @param {number|string} id 文件 ID
 * @param {string} fallbackName 后端响应头缺失时的兜底文件名
 */
export async function downloadFile(id, fallbackName = 'download') {
  const res = await http.get(`/api/files/${id}/download`, { responseType: 'blob' })
  const blob = res instanceof Blob ? res : new Blob([res])
  // 服务端错误时返回 JSON（blob 形态）：解析业务提示并中止下载
  if (blob.type && blob.type.includes('json')) {
    const text = await blob.text()
    try {
      const body = JSON.parse(text)
      throw new Error(body.message || '下载失败')
    } catch (e) {
      if (e instanceof SyntaxError) {
        throw new Error('下载失败：' + text.slice(0, 100))
      }
      throw e
    }
  }
  const link = document.createElement('a')
  const url = window.URL.createObjectURL(blob)
  link.href = url
  link.download = fallbackName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}
