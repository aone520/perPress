<!--
  参数提取器测试面板（公共组件）：
  - 父组件通过 ref 调用 open(extractor) 打开，传入提取器对象引用（面板内改动直接同步回主表单）
  - 粘贴一段真实响应示例，边填表达式边实时看到提取结果（本地解析，模拟 JMeter 语义）
  - 内置常用模板一键填充（JSON/边界/正则三类），新手不用先学表达式
  - 本地解析说明：JSON 支持 $.a.b / [0] / [*] / $..key 子集；正则按 JS 语义（与 JMeter Java 正则略有差异，以实际压测为准）
-->
<template>
  <el-dialog
    v-model="visible"
    title="提取器测试"
    width="720px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <div class="tester">
      <!-- 步骤提示 -->
      <div class="step-hint">
        <span class="step-badge">1</span> 粘贴一段真实响应示例
        <span class="step-badge">2</span> 点击模板或填写表达式
        <span class="step-badge">3</span> 下方实时查看提取结果
      </div>

      <!-- 响应示例输入 -->
      <el-input
        v-model="sampleText"
        type="textarea"
        :rows="7"
        spellcheck="false"
        placeholder='粘贴接口返回内容，如：{"code":0,"data":{"token":"abc123","list":[{"id":1}]}}'
        class="sample-input"
      />

      <!-- 常用模板 -->
      <div class="tpl-section">
        <div class="tpl-title">
          常用模板（点击一键填充）
          <el-tooltip content="模板覆盖当前表达式；按实际响应结构调整路径" placement="top">
            <el-icon class="tpl-help"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
        <div class="tpl-list">
          <span
            v-for="tpl in currentTemplates"
            :key="tpl.label"
            class="tpl-chip"
            @click="applyTemplate(tpl)"
          >
            <span class="tpl-label">{{ tpl.label }}</span>
            <span class="tpl-desc">{{ tpl.desc }}</span>
          </span>
        </div>
      </div>

      <!-- 当前表达式参数（与主表单同对象引用，修改即时同步） -->
      <div class="params">
        <div class="param-row">
          <span class="param-label">{{ expressionLabel }}</span>
          <el-input
            v-model="extractor.expression"
            placeholder="提取表达式"
            clearable
            spellcheck="false"
            class="param-input mono"
          />
          <el-input
            v-if="extractor.type === 'BOUNDARY'"
            v-model="extractor.rightBoundary"
            placeholder="右边界"
            clearable
            class="param-short mono"
          />
          <el-input
            v-if="extractor.type === 'REGEX'"
            v-model="extractor.template"
            placeholder="模板 $1$"
            clearable
            class="param-short mono"
          />
        </div>
        <div class="param-row">
          <span class="param-label">匹配序号</span>
          <el-select v-model="extractor.matchNumber" class="param-select">
            <el-option :value="1" label="1（第 1 个匹配）" />
            <el-option :value="0" label="0（随机）" />
            <el-option :value="-1" label="-1（全部匹配）" />
          </el-select>
          <span class="param-tip">未命中时使用默认值 {{ extractor.defaultValue || 'NOT_FOUND' }}</span>
        </div>
      </div>

      <!-- 实时提取结果 -->
      <div class="result">
        <div class="result-title">
          提取结果
          <span class="result-ref" v-if="extractor.refName">→ ${{ '{' + extractor.refName + '}' }}</span>
        </div>
        <template v-if="result.error">
          <div class="result-line error">{{ result.error }}</div>
        </template>
        <template v-else-if="result.all">
          <div v-for="(item, i) in result.all" :key="i" class="result-line ok">
            <span class="result-key">${{ '{' + extractor.refName + '_' + (i + 1) + '}' }}</span>
            <span class="result-value">{{ item }}</span>
          </div>
        </template>
        <template v-else-if="result.hit">
          <div class="result-line ok">
            <span class="result-key">${{ '{' + extractor.refName + '}' }}</span>
            <span class="result-value">{{ result.value }}</span>
          </div>
        </template>
        <template v-else>
          <div class="result-line miss">未命中：将使用默认值「{{ extractor.defaultValue || 'NOT_FOUND' }}」，请调整表达式或核对示例内容</div>
        </template>
      </div>

      <div class="foot-note">
        说明：面板在浏览器本地模拟 JMeter 提取语义；响应码/响应头来源请粘贴对应文本（如 200）进行模拟。
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'

/** 弹窗可见状态 */
const visible = ref(false)

/** 当前正在测试的提取器对象（与主表单同引用，修改即时同步） */
const extractor = ref(createBlankExtractor())

/** 响应示例文本（预置一段 JSON，便于立即体验） */
const sampleText = ref(
  '{\n  "code": 0,\n  "message": "success",\n  "data": {\n    "token": "abc-123-xyz",\n    "userId": 10086,\n    "list": [\n      { "id": 1, "name": "alpha" },\n      { "id": 2, "name": "beta" }\n    ]\n  }\n}'
)

/**
 * 创建空白提取器（与主表单结构一致，防止未 open 时模板访问空对象）
 * @returns {Object} 提取器对象
 */
function createBlankExtractor() {
  return {
    type: 'JSON',
    refName: '',
    expression: '',
    rightBoundary: '',
    source: 'RESPONSE_BODY',
    template: '$1$',
    matchNumber: 1,
    defaultValue: 'NOT_FOUND'
  }
}

/**
 * 打开测试面板（父组件 ref 调用）
 * @param {Object} target 主表单中的提取器对象引用
 */
function open(target) {
  if (target) {
    extractor.value = target
  }
  visible.value = true
}

defineExpose({ open })

/** 表达式输入框标签（按类型切换文案） */
const expressionLabel = computed(() =>
  extractor.value.type === 'JSON'
    ? 'JSONPath'
    : extractor.value.type === 'BOUNDARY'
      ? '左边界'
      : '正则'
)

/** JSON 提取内置模板 */
const JSON_TEMPLATES = [
  { label: '$.token', expression: '$.token', desc: '顶层字段' },
  { label: '$.data.token', expression: '$.data.token', desc: '嵌套字段' },
  { label: '$.data.list[0].id', expression: '$.data.list[0].id', desc: '数组第 1 个' },
  { label: '$..id', expression: '$..id', desc: '递归查找' },
  { label: '$.data.list[*].name', expression: '$.data.list[*].name', desc: '数组全部' }
]

/** 边界提取内置模板（label 为展示、apply 字段为填充内容） */
const BOUNDARY_TEMPLATES = [
  { label: '"token":"', expression: '"token":"', rightBoundary: '"', desc: 'JSON 字段值' },
  { label: '<token>', expression: '<token>', rightBoundary: '</token>', desc: 'XML 标签' },
  { label: 'token=', expression: 'token=', rightBoundary: '&', desc: 'URL 参数' }
]

/** 正则提取内置模板 */
const REGEX_TEMPLATES = [
  { label: 'token":"(\\w+)"', expression: 'token":"(\\w+)"', template: '$1$', desc: '字段捕获' },
  { label: 'id=(\\d+)', expression: 'id=(\\d+)', template: '$1$', desc: '数字参数' },
  { label: 'Bearer\\s+(\\S+)', expression: 'Bearer\\s+(\\S+)', template: '$1$', desc: '令牌前缀' }
]

/** 当前类型对应的模板列表 */
const currentTemplates = computed(() => {
  if (extractor.value.type === 'BOUNDARY') {
    return BOUNDARY_TEMPLATES
  }
  if (extractor.value.type === 'REGEX') {
    return REGEX_TEMPLATES
  }
  return JSON_TEMPLATES
})

/**
 * 应用模板：填充表达式（及类型特有字段）
 * @param {Object} tpl 模板对象
 */
function applyTemplate(tpl) {
  extractor.value.expression = tpl.expression
  if (tpl.rightBoundary !== undefined) {
    extractor.value.rightBoundary = tpl.rightBoundary
  }
  if (tpl.template !== undefined) {
    extractor.value.template = tpl.template
  }
}

/** 实时提取结果：{hit, value, all, error} */
const result = computed(() => runExtract(sampleText.value, extractor.value))

/**
 * 执行本地提取（模拟 JMeter 语义）
 * @param {string} text   响应示例文本
 * @param {Object} ex     提取器定义
 * @returns {{hit:boolean,value:string,all:string[]|null,error:string|null}} 提取结果
 */
function runExtract(text, ex) {
  if (!text) {
    return { hit: false, value: '', all: null, error: null }
  }
  if (!(ex.expression || '').trim()) {
    return { hit: false, value: '', all: null, error: null }
  }
  try {
    let values = []
    if (ex.type === 'JSON') {
      values = extractJson(text, ex.expression.trim())
    } else if (ex.type === 'BOUNDARY') {
      values = extractBoundary(text, ex.expression, ex.rightBoundary)
    } else {
      values = extractRegex(text, ex.expression, ex.template)
    }
    if (!values.length) {
      return { hit: false, value: '', all: null, error: null }
    }
    const n = ex.matchNumber == null ? 1 : ex.matchNumber
    if (n === -1) {
      return { hit: true, value: '', all: values.map(stringify), error: null }
    }
    if (n === 0) {
      return { hit: true, value: stringify(values[Math.floor(Math.random() * values.length)]), all: null, error: null }
    }
    if (n > values.length) {
      return { hit: false, value: '', all: null, error: null }
    }
    return { hit: true, value: stringify(values[n - 1]), all: null, error: null }
  } catch (e) {
    return { hit: false, value: '', all: null, error: '表达式解析失败：' + (e?.message || e) }
  }
}

/**
 * 值转展示字符串（对象/数组保持 JSON 形态）
 * @param {*} value 提取到的值
 * @returns {string} 展示文本
 */
function stringify(value) {
  if (value == null) {
    return ''
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

/**
 * JSON 提取：解析文本为 JSON 后按 JSONPath 子集求值
 * （支持 $.a.b / ['k'] / [0] / [*] / $..key 递归）
 * @param {string} text       响应文本
 * @param {string} expression JSONPath 表达式
 * @returns {Array} 命中的值列表
 */
function extractJson(text, expression) {
  const obj = JSON.parse(text)
  return evalJsonPath(obj, expression)
}

/**
 * JSONPath 子集求值器（无三方依赖）
 * @param {*}    obj    解析后的 JSON 对象
 * @param {string} path JSONPath 表达式（$ 开头）
 * @returns {Array} 命中的值列表
 */
function evalJsonPath(obj, path) {
  const tokens = []
  const s = path.replace(/\s/g, '')
  if (!s.startsWith('$')) {
    throw new Error('JSONPath 需以 $ 开头')
  }
  let i = 1
  while (i < s.length) {
    if (s[i] === '.') {
      if (s[i + 1] === '.') {
        // 递归后代：..key
        i += 2
        const start = i
        while (i < s.length && s[i] !== '.' && s[i] !== '[') {
          i += 1
        }
        tokens.push({ type: 'desc', key: s.slice(start, i) })
      } else {
        i += 1
        if (s[i] === '*') {
          tokens.push({ type: 'wild' })
          i += 1
          continue
        }
        const start = i
        while (i < s.length && s[i] !== '.' && s[i] !== '[') {
          i += 1
        }
        tokens.push({ type: 'child', key: s.slice(start, i) })
      }
    } else if (s[i] === '[') {
      const end = s.indexOf(']', i)
      if (end < 0) {
        throw new Error('中括号未闭合')
      }
      const inner = s.slice(i + 1, end)
      if (inner === '*') {
        tokens.push({ type: 'wild' })
      } else if (inner.startsWith("'") || inner.startsWith('"')) {
        tokens.push({ type: 'child', key: inner.slice(1, -1) })
      } else if (/^-?\d+$/.test(inner)) {
        tokens.push({ type: 'index', n: parseInt(inner, 10) })
      } else {
        throw new Error('不支持下标语法：[' + inner + ']')
      }
      i = end + 1
    } else {
      throw new Error('非法字符：' + s[i])
    }
  }
  let results = [obj]
  for (const token of tokens) {
    const next = []
    for (const node of results) {
      if (token.type === 'child') {
        if (node != null && typeof node === 'object' && node[token.key] !== undefined) {
          next.push(node[token.key])
        }
      } else if (token.type === 'index') {
        if (Array.isArray(node) && node[token.n] !== undefined) {
          next.push(node[token.n])
        }
      } else if (token.type === 'wild') {
        if (Array.isArray(node)) {
          next.push(...node)
        } else if (node && typeof node === 'object') {
          next.push(...Object.values(node))
        }
      } else if (token.type === 'desc') {
        collectDescendant(node, token.key, next)
      }
    }
    results = next
  }
  return results
}

/**
 * 递归收集指定 key 的所有值（$..key）
 * @param {*}    node 当前节点
 * @param {string} key 目标键名
 * @param {Array} out  输出收集列表
 */
function collectDescendant(node, key, out) {
  if (Array.isArray(node)) {
    node.forEach((item) => collectDescendant(item, key, out))
    return
  }
  if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) {
      if (k === key) {
        out.push(v)
      }
      collectDescendant(v, key, out)
    }
  }
}

/**
 * 边界提取：左右边界之间的内容（多次命中全部收集）
 * @param {string} text          响应文本
 * @param {string} leftBoundary  左边界
 * @param {string} rightBoundary 右边界
 * @returns {Array} 命中的值列表
 */
function extractBoundary(text, leftBoundary, rightBoundary) {
  const left = (leftBoundary || '').trim()
  const right = (rightBoundary || '').trim()
  if (!left || !right) {
    throw new Error('边界提取需同时填写左边界与右边界')
  }
  const values = []
  let pos = 0
  for (;;) {
    const l = text.indexOf(left, pos)
    if (l < 0) {
      break
    }
    const start = l + left.length
    const r = text.indexOf(right, start)
    if (r < 0) {
      break
    }
    values.push(text.slice(start, r))
    pos = r + right.length
  }
  return values
}

/**
 * 正则提取：JS RegExp 全局匹配 + JMeter 模板（$1$ 形式）替换捕获组
 * @param {string} text      响应文本
 * @param {string} pattern   正则表达式
 * @param {string} template  取值模板（如 $1$）
 * @returns {Array} 命中的值列表
 */
function extractRegex(text, pattern, template) {
  const re = new RegExp(pattern, 'g')
  const values = []
  for (const match of text.matchAll(re)) {
    values.push(applyJmeterTemplate(template || '$1$', match))
  }
  return values
}

/**
 * 应用 JMeter 模板：把 $N$（N=0 整体 / 1..n 捕获组）替换为匹配结果
 * @param {string} template 模板字符串
 * @param {Array}  match    单次正则匹配结果（match[0]=整体，match[n]=捕获组）
 * @returns {string} 模板替换后的值
 */
function applyJmeterTemplate(template, match) {
  return template.replace(/\$(\d+)\$/g, (_, n) => {
    const idx = parseInt(n, 10)
    return match[idx] == null ? '' : match[idx]
  })
}
</script>

<style scoped>
.tester {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 步骤提示条 */
.step-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--pp-text-secondary);
  flex-wrap: wrap;
}
.step-badge {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background-color: var(--el-color-primary-light-8);
  color: var(--pp-primary);
  font-size: 11px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.step-hint .step-badge:not(:first-child) {
  margin-left: 10px;
}

.sample-input :deep(textarea) {
  font-family: var(--pp-font-mono);
  font-size: 12px;
}

/* 模板区 */
.tpl-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tpl-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--pp-text-regular);
}
.tpl-help {
  font-size: 13px;
  color: var(--pp-text-placeholder);
  cursor: help;
}
.tpl-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tpl-chip {
  display: inline-flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  border: 1px solid var(--pp-border);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.tpl-chip:hover {
  border-color: var(--pp-primary);
  background-color: var(--el-color-primary-light-9);
}
.tpl-label {
  font-family: var(--pp-font-mono);
  font-size: 12px;
  color: var(--pp-primary);
}
.tpl-desc {
  font-size: 11px;
  color: var(--pp-text-secondary);
}

/* 参数区 */
.params {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background-color: var(--pp-surface-2);
  border: 1px solid var(--pp-border);
  border-radius: 8px;
}
.param-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.param-label {
  width: 56px;
  font-size: 12px;
  color: var(--pp-text-secondary);
  flex-shrink: 0;
}
.param-input {
  flex: 1;
  min-width: 240px;
}
.param-short {
  width: 140px;
}
.param-select {
  width: 170px;
}
.param-tip {
  font-size: 12px;
  color: var(--pp-text-placeholder);
}

/* 结果区 */
.result {
  border: 1px solid var(--pp-border);
  border-radius: 8px;
  padding: 12px;
  min-height: 88px;
  background-color: var(--pp-surface);
}
.result-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--pp-text-regular);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.result-ref {
  font-family: var(--pp-font-mono);
  font-weight: 500;
  color: var(--pp-primary);
}
.result-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 5px 8px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.5;
}
.result-line.ok {
  background-color: var(--el-color-success-light-9);
}
.result-line.miss {
  background-color: var(--el-color-warning-light-9);
  color: var(--pp-text-regular);
}
.result-line.error {
  background-color: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 12px;
}
.result-key {
  font-family: var(--pp-font-mono);
  font-size: 12px;
  color: var(--pp-success);
  flex-shrink: 0;
}
.result-value {
  font-family: var(--pp-font-mono);
  word-break: break-all;
  color: var(--pp-text-primary);
}

.foot-note {
  font-size: 12px;
  color: var(--pp-text-placeholder);
  line-height: 1.5;
}
</style>
