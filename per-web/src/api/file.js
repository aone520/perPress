/**
 * 文件库相关接口封装：文件上传、分页查询与删除
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
