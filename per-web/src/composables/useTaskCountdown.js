import { onMounted, onUnmounted, shallowRef } from 'vue'

/**
 * 读取任务计划时长（秒）。阶梯压测与后端 SteppedPlan.totalDuration 保持同一算法。
 *
 * @param {object} task 任务详情或列表项
 * @returns {number|null} 合法的计划时长；配置不完整时返回 null
 */
export function getTaskDurationSeconds(task) {
  const config = task?.config || {}
  if (task?.mode !== 'STEPPED') {
    const duration = Number(config.durationSeconds)
    return Number.isFinite(duration) && duration > 0 ? duration : null
  }

  const start = Number(config.start)
  const step = Number(config.step)
  const stepSeconds = Number(config.stepSeconds)
  const peak = Number(config.peak)
  const peakSeconds = Number(config.peakSeconds)
  if (
    ![start, step, stepSeconds, peak, peakSeconds].every((value) => Number.isFinite(value) && value > 0) ||
    peak < start
  ) {
    return null
  }

  const steps = Math.max(0, Math.ceil((peak - start) / step))
  return steps * stepSeconds + peakSeconds
}

/**
 * 根据任务实际开始时间计算剩余秒数。仅 RUNNING 状态展示倒计时。
 *
 * @param {object} task 任务详情或列表项
 * @param {number} nowMs 当前时间戳，便于测试
 * @returns {number|null} 剩余秒数；不可计算时返回 null
 */
export function getTaskRemainingSeconds(task, nowMs = Date.now()) {
  if (task?.status !== 'RUNNING' || !task.startTime) return null

  const startMs = new Date(task.startTime).getTime()
  const durationSeconds = getTaskDurationSeconds(task)
  if (!Number.isFinite(startMs) || durationSeconds === null) return null

  return Math.max(0, Math.ceil((startMs + durationSeconds * 1000 - nowMs) / 1000))
}

/**
 * 将秒数格式化为 mm:ss；超过一小时后显示 h:mm:ss。
 *
 * @param {number} seconds 秒数
 * @returns {string} 倒计时文案
 */
export function formatTaskCountdown(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  const pad = (value) => String(value).padStart(2, '0')

  return hours > 0
    ? hours + ':' + pad(minutes) + ':' + pad(secs)
    : pad(minutes) + ':' + pad(secs)
}

/**
 * 页面级倒计时时钟。一个页面仅维护一个定时器，列表中所有任务共享同一时钟。
 *
 * @returns {{ stopButtonLabel: (task: object, baseText?: string) => string }}
 */
export function useTaskCountdown() {
  const nowMs = shallowRef(Date.now())
  let timer = null

  onMounted(() => {
    timer = setInterval(() => {
      nowMs.value = Date.now()
    }, 1000)
  })

  onUnmounted(() => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  })

  /**
   * 生成停止按钮文案。计划时间耗尽但任务尚未进入终态时提示“等待收尾”。
   *
   * @param {object} task 任务详情或列表项
   * @param {string} baseText 按钮基础文案
   * @returns {string} 按钮展示文案
   */
  function stopButtonLabel(task, baseText = '停止任务') {
    if (task?.status === 'PREPARING') return baseText + '（准备中）'
    if (task?.status === 'STOPPING') return '停止中'

    const remaining = getTaskRemainingSeconds(task, nowMs.value)
    if (remaining === null) return baseText
    if (remaining === 0) return baseText + '（等待收尾）'
    return baseText + '（距结束 ' + formatTaskCountdown(remaining) + '）'
  }

  return { stopButtonLabel }
}
