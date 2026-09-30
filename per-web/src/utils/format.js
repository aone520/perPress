/**
 * 通用格式化工具集：时间与字节数的展示格式化
 */

/**
 * 将时间值格式化为 YYYY-MM-DD HH:mm:ss
 * @param {string|number|Date} value 待格式化的时间（字符串 / 时间戳 / Date 对象）
 * @returns {string} 格式化结果；空值或无法解析时返回 '-'
 */
export function formatDateTime(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }
  const pad = (n) => String(n).padStart(2, '0')
  const y = date.getFullYear()
  const M = pad(date.getMonth() + 1)
  const d = pad(date.getDate())
  const h = pad(date.getHours())
  const m = pad(date.getMinutes())
  const s = pad(date.getSeconds())
  return `${y}-${M}-${d} ${h}:${m}:${s}`
}

/**
 * 将时间戳格式化为 HH:mm:ss（兼容秒级与毫秒级：值 > 1e12 视为毫秒，否则按秒 ×1000）
 * @param {number|string} tsOrSeconds 秒级或毫秒级时间戳
 * @returns {string} HH:mm:ss 格式结果；空值或非法数值返回 '-'
 */
export function formatClock(tsOrSeconds) {
  const n = Number(tsOrSeconds)
  if (!Number.isFinite(n) || n <= 0) {
    return '-'
  }
  const ms = n > 1e12 ? n : n * 1000
  const date = new Date(ms)
  if (Number.isNaN(date.getTime())) {
    return '-'
  }
  const pad = (v) => String(v).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/**
 * 将字节数格式化为人类可读字符串（自动换算 B/KB/MB/GB/TB，保留两位小数）
 * @param {number|string} bytes 字节数
 * @returns {string} 格式化结果；空值或非法数值返回 '-'
 */
export function formatBytes(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n < 0) {
    return '-'
  }
  if (n < 1024) {
    return `${n} B`
  }
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = n
  let unitIndex = -1
  do {
    value /= 1024
    unitIndex += 1
  } while (value >= 1024 && unitIndex < units.length - 1)
  return `${value.toFixed(2)} ${units[unitIndex]}`
}
