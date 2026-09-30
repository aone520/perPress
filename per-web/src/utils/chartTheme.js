/**
 * ECharts 统一主题：与设计令牌（src/styles/tokens.css）协调的低饱和色板与轴/提示样式，
 * 监控大屏与报告中心共用，保证全平台图表视觉一致
 */

/** 核心图表色（靛蓝主色 + 青/琥珀/玫红/紫/灰蓝低饱和辅助色） */
export const CHART_COLORS = {
  primary: '#5e6ad2',
  teal: '#3b9e9e',
  amber: '#d79a2e',
  rose: '#d2554f',
  violet: '#8a6fd1',
  slate: '#667085'
}

/** 系列默认色板（多系列自动轮转） */
export const CHART_PALETTE = [
  CHART_COLORS.primary,
  CHART_COLORS.teal,
  CHART_COLORS.amber,
  CHART_COLORS.rose,
  CHART_COLORS.violet,
  CHART_COLORS.slate
]

/**
 * 类目轴通用配置：极浅轴线 + 灰字 + 重叠标签自动隐藏
 * @param {Array} [data] 类目数据（时间等）
 * @returns {Object} ECharts xAxis 选项
 */
export function chartCategoryAxis(data = []) {
  return {
    type: 'category',
    data,
    axisLine: { lineStyle: { color: '#e9ebf0' } },
    axisTick: { show: false },
    axisLabel: { color: '#7a8194', fontSize: 11, hideOverlap: true }
  }
}

/**
 * 数值轴通用配置：极浅分割线 + 灰字 + 可选单位
 * @param {string} [name] 轴名称（如 TPS / ms）
 * @returns {Object} ECharts yAxis 选项
 */
export function chartValueAxis(name = '') {
  return {
    type: 'value',
    name,
    nameTextStyle: { color: '#7a8194', fontSize: 11 },
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#7a8194', fontSize: 11 },
    splitLine: { lineStyle: { color: '#f2f4f7' } }
  }
}

/**
 * 提示框通用配置：白底细边框圆角 + 轴触发 + 可选数值单位
 * @param {string} [unit] 数值单位（ms / % 等）
 * @returns {Object} ECharts tooltip 选项
 */
export function chartTooltip(unit = '') {
  return {
    trigger: 'axis',
    backgroundColor: '#ffffff',
    borderColor: '#e9ebf0',
    borderRadius: 8,
    padding: [8, 12],
    textStyle: { color: '#1a1d24', fontSize: 12 },
    valueFormatter: (value) => (value == null ? '-' : `${value}${unit}`)
  }
}

/**
 * 图例通用配置：置顶右对齐 + 常规灰字
 * @param {Array} [data] 图例项
 * @returns {Object} ECharts legend 选项
 */
export function chartLegend(data = []) {
  return {
    top: 4,
    right: 8,
    icon: 'roundRect',
    itemWidth: 12,
    itemHeight: 4,
    itemGap: 16,
    textStyle: { color: '#4a5060', fontSize: 12 },
    data
  }
}

/**
 * 折线系列通用片段：直线连接（真实呈现毛刺）、无数据点符号、2px 线宽
 * @param {string} name   系列名
 * @param {Array}  data   数据
 * @param {string} color  线色
 * @param {Object} [extra] 额外覆盖项（如 areaStyle/step）
 * @returns {Object} ECharts series 选项
 */
export function chartLine(name, data, color, extra = {}) {
  return {
    name,
    type: 'line',
    smooth: false,
    showSymbol: false,
    symbolSize: 5,
    lineStyle: { width: 2 },
    itemStyle: { color },
    data,
    ...extra
  }
}

/** 四张图共用 grid 版式 */
export const CHART_GRID = { left: 52, right: 24, top: 42, bottom: 28 }
