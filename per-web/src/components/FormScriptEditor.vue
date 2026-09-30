<!--
  表单脚本定义编辑器（公共组件）：
  - v-model 绑定 formDef 对象（threadGroupName / thinkTimeMs / groups）
  - 编排分组：组头可命名并选择执行方式（SERIAL 组内串行 / PARALLEL 组内接口并行），组数 ≤ 10
  - 采样器卡片支持组内增删、上移下移、跨组移动；卡片内含请求头/请求体/响应断言/参数文件引用动态行
  - 兼容旧数据：无 groups 的旧 formDef 打开时自动包成单个 SERIAL 组
  - 额外导出 validateFormDef / sanitizeFormDef / METHOD_OPTIONS 供父组件使用
-->
<template>
  <div class="form-script-editor">
    <!-- 线程组与思考时间 -->
    <el-form label-width="90px">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="线程组名">
            <el-input
              v-model="def.threadGroupName"
              placeholder="如：订单压测线程组"
              maxlength="64"
              clearable
              :disabled="disabled"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="思考时间">
            <el-input-number v-model="def.thinkTimeMs" :min="0" :step="100" :disabled="disabled" />
            <span class="unit-text">毫秒（0 表示不启用）</span>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <!-- 编排分组卡片列表 -->
    <div class="section-title">
      接口编排分组（{{ def.groups.length }}）
      <el-button
        type="primary"
        size="small"
        :icon="Plus"
        :disabled="disabled || def.groups.length >= 10"
        @click="addGroup"
      >
        添加分组
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      class="group-tip"
      description="串行组：组内接口按顺序执行，整组共享压力；并行组：组内每个接口各自独立线程组同时施压，可在创建任务时单独设置流量占比"
    />

    <el-empty v-if="!def.groups.length" description="请至少添加 1 个分组" :image-size="60" />
    <el-card
      v-for="(group, gi) in def.groups"
      :key="group._key"
      shadow="never"
      class="group-card"
    >
      <div class="group-head">
        <div class="group-head-left">
          <span class="group-title">分组 {{ gi + 1 }}</span>
          <el-input
            v-model="group.name"
            placeholder="组名（如：浏览链路）"
            maxlength="32"
            class="group-name-input"
            :disabled="disabled"
          />
          <el-radio-group v-model="group.execution" :disabled="disabled" size="small">
            <el-radio-button value="SERIAL">组内串行</el-radio-button>
            <el-radio-button value="PARALLEL">组内并行</el-radio-button>
          </el-radio-group>
        </div>
        <div class="group-head-actions">
          <el-button
            size="small"
            :icon="Plus"
            :disabled="disabled"
            @click="addSampler(gi)"
          >
            添加采样器
          </el-button>
          <el-button
            link
            type="danger"
            :disabled="disabled"
            @click="removeGroup(gi)"
          >
            删除分组
          </el-button>
        </div>
      </div>

      <el-empty
        v-if="!group.samplers.length"
        description="该分组暂无采样器，请添加"
        :image-size="40"
      />
      <el-card
        v-for="(sampler, si) in group.samplers"
        :key="sampler._key"
        shadow="never"
        class="sampler-card"
      >
        <div class="sampler-head">
          <span class="sampler-title">采样器 {{ si + 1 }}</span>
          <div class="sampler-actions">
            <el-button link :disabled="disabled || si === 0" @click="moveSampler(gi, si, -1)">上移</el-button>
            <el-button link :disabled="disabled || si === group.samplers.length - 1" @click="moveSampler(gi, si, 1)">下移</el-button>
            <el-dropdown
              v-if="def.groups.length > 1"
              trigger="click"
              @command="(targetGi) => moveSamplerToGroup(gi, si, targetGi)"
            >
              <el-button link type="primary" :disabled="disabled">移至组 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="(target, ti) in def.groups"
                    :key="target._key"
                    :command="ti"
                    :disabled="ti === gi"
                  >
                    {{ targetGroupLabel(ti, target) }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button link type="danger" :disabled="disabled" @click="removeSampler(gi, si)">删除</el-button>
          </div>
        </div>
        <el-form label-width="80px" label-position="left" class="sampler-form">
          <el-row :gutter="12">
            <el-col :span="8">
              <el-form-item label="名称">
                <el-input v-model="sampler.name" placeholder="采样器名称" maxlength="64" clearable :disabled="disabled" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="方法">
                <el-select v-model="sampler.method" :disabled="disabled">
                  <el-option v-for="m in METHOD_OPTIONS" :key="m" :label="m" :value="m" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="10">
              <el-form-item label="URL" required>
                <el-input v-model="sampler.url" placeholder="http://host:port/path?a=b" clearable :disabled="disabled" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 请求头动态行 -->
          <div class="sub-title">
            请求头
            <el-button
              link
              type="primary"
              size="small"
              :disabled="disabled"
              @click="sampler.headers.push({ k: '', v: '' })"
            >
              + 添加
            </el-button>
          </div>
          <div v-for="(header, hi) in sampler.headers" :key="hi" class="dyn-row">
            <el-input v-model="header.k" placeholder="Header 名（如 Content-Type）" class="dyn-input" :disabled="disabled" />
            <el-input v-model="header.v" placeholder="Header 值" class="dyn-input" :disabled="disabled" />
            <el-button link type="danger" :disabled="disabled" @click="sampler.headers.splice(hi, 1)">删除</el-button>
          </div>

          <!-- 请求体 -->
          <div class="sub-title">请求体（POST/PUT 原始文本）</div>
          <el-input
            v-model="sampler.body"
            type="textarea"
            :rows="2"
            placeholder='如 {"userId": ${userId}}（可选）'
            :disabled="disabled"
          />

          <!-- 断言动态行 -->
          <div class="sub-title">
            响应断言
            <el-button
              link
              type="primary"
              size="small"
              :disabled="disabled"
              @click="sampler.assertions.push({ type: 'TEXT', expect: '' })"
            >
              + 添加
            </el-button>
          </div>
          <div v-for="(assertion, ai) in sampler.assertions" :key="ai" class="dyn-row">
            <el-select v-model="assertion.type" class="w-140" :disabled="disabled">
              <el-option label="文本包含(TEXT)" value="TEXT" />
              <el-option label="响应码(CODE)" value="CODE" />
            </el-select>
            <el-input v-model="assertion.expect" placeholder="期望值（如 success / 200）" class="dyn-input" :disabled="disabled" />
            <el-button link type="danger" :disabled="disabled" @click="sampler.assertions.splice(ai, 1)">删除</el-button>
          </div>

          <!-- 参数提取动态行：从本接口响应/请求提取变量，串行组内后续接口以 ${引用名} 使用；并行组独立线程不支持 -->
          <div class="sub-title">
            参数提取
            <el-button
              v-if="def.groups[gi].execution !== 'PARALLEL'"
              link
              type="primary"
              size="small"
              :disabled="disabled"
              @click="sampler.extractors.push(createEmptyExtractor())"
            >
              + 添加提取器
            </el-button>
          </div>
          <div v-if="def.groups[gi].execution === 'PARALLEL'" class="extractor-tip">
            并行组内接口各自独立线程执行，JMeter 变量不互通，不支持参数提取关联；如需关联请将接口放入串行组
          </div>
          <div v-for="(ex, ei) in sampler.extractors" :key="ei" class="extractor-row">
            <div class="dyn-row">
              <el-select
                v-model="ex.type"
                class="w-110"
                :disabled="disabled"
                @change="handleExtractorFieldChange(ex)"
              >
                <el-option
                  v-for="t in EXTRACTOR_TYPE_OPTIONS"
                  :key="t.value"
                  :label="t.label"
                  :value="t.value"
                />
              </el-select>
              <el-input
                v-model="ex.refName"
                placeholder="引用名（后续接口 ${引用名} 使用）"
                class="dyn-input"
                clearable
                :disabled="disabled"
              />
              <el-select
                v-model="ex.source"
                class="w-130"
                :disabled="disabled"
                @change="handleExtractorFieldChange(ex)"
              >
                <el-option
                  v-for="s in EXTRACTOR_SOURCE_OPTIONS"
                  :key="s.value"
                  :label="s.label"
                  :value="s.value"
                  :disabled="ex.type === 'JSON' && s.value === 'URL'"
                />
              </el-select>
              <el-button link type="danger" :disabled="disabled" @click="sampler.extractors.splice(ei, 1)">删除</el-button>
            </div>
            <div class="dyn-row">
              <el-input
                v-model="ex.expression"
                :placeholder="
                  ex.type === 'JSON'
                    ? 'JSONPath 表达式（如 $.data.token）'
                    : ex.type === 'BOUNDARY'
                      ? '左边界（提取右边界之前的内容）'
                      : '正则表达式（用分组捕获，如 token=(\\w+)）'
                "
                class="dyn-input"
                clearable
                :disabled="disabled"
              />
              <el-input
                v-if="ex.type === 'BOUNDARY'"
                v-model="ex.rightBoundary"
                placeholder="右边界"
                class="w-160"
                clearable
                :disabled="disabled"
              />
              <el-input
                v-if="ex.type === 'REGEX'"
                v-model="ex.template"
                placeholder="模板 $1$"
                class="w-110"
                clearable
                :disabled="disabled"
              />
              <el-input-number
                v-model="ex.matchNumber"
                :min="-1"
                :step="1"
                size="small"
                controls-position="right"
                class="w-110"
                :disabled="disabled"
              />
              <el-input
                v-model="ex.defaultValue"
                placeholder="未命中默认值"
                class="w-150"
                clearable
                :disabled="disabled"
              />
            </div>
          </div>

          <!-- 参数文件引用动态行（CSV/TXT 均可选） -->
          <div class="sub-title">
            参数文件引用
            <el-button
              link
              type="primary"
              size="small"
              :disabled="disabled"
              @click="sampler.csvRefs.push(createEmptyCsvRef())"
            >
              + 添加参数文件
            </el-button>
          </div>
          <div v-for="(csvRef, ci) in sampler.csvRefs" :key="ci" class="csv-ref-row">
            <div class="dyn-row">
              <el-select v-model="csvRef.fileId" placeholder="选择参数文件（支持 CSV/TXT）" filterable class="dyn-input" :disabled="disabled">
                <el-option
                  v-for="item in fileOptions"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
              <el-input v-model="csvRef.varNames" placeholder="变量名，逗号分隔（如 userId,userName）" class="dyn-input" :disabled="disabled" />
              <el-button link type="danger" :disabled="disabled" @click="sampler.csvRefs.splice(ci, 1)">删除</el-button>
            </div>
            <div class="dyn-row">
              <el-input v-model="csvRef.delimiter" placeholder="分隔符" class="w-120" :disabled="disabled" />
              <div class="switch-item">
                <span class="label-text">循环读取</span>
                <el-switch v-model="csvRef.recycle" :disabled="disabled" />
              </div>
              <div class="switch-item">
                <span class="label-text">忽略首行</span>
                <el-switch v-model="csvRef.ignoreFirstLine" :disabled="disabled" />
              </div>
              <el-select v-model="csvRef.shareMode" class="w-180" :disabled="disabled">
                <el-option label="所有线程共享" value="shareMode.all" />
                <el-option label="线程组内共享" value="shareMode.group" />
              </el-select>
            </div>
          </div>
        </el-form>
      </el-card>
    </el-card>
  </div>
</template>

<script>
/**
 * HTTP 请求方法枚举（对齐 JMeter HTTP Sampler 支持的全部方法）
 */
export const METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS', 'TRACE']

/**
 * 提取器类型选项：REGEX 正则 / JSON JSONPath / BOUNDARY 左右边界
 */
export const EXTRACTOR_TYPE_OPTIONS = [
  { value: 'JSON', label: 'JSON提取' },
  { value: 'REGEX', label: '正则提取' },
  { value: 'BOUNDARY', label: '边界提取' }
]

/**
 * 提取来源选项（JSON 提取器不支持 URL，由模板侧联动过滤）
 */
export const EXTRACTOR_SOURCE_OPTIONS = [
  { value: 'RESPONSE_BODY', label: '响应体' },
  { value: 'RESPONSE_HEADERS', label: '响应头' },
  { value: 'REQUEST_HEADERS', label: '请求头' },
  { value: 'URL', label: '请求地址' },
  { value: 'RESPONSE_CODE', label: '响应码' },
  { value: 'RESPONSE_MESSAGE', label: '响应消息' }
]

/**
 * 校验表单定义完整性：至少 1 个分组、每组至少 1 个采样器且 URL 非空、提取器行填写完整
 * @param {Object} def 表单定义（threadGroupName/thinkTimeMs/groups）
 * @returns {string|null} 错误提示文案，null 表示校验通过
 */
export function validateFormDef(def) {
  const groups = def?.groups || []
  if (!groups.length) {
    return '请至少添加 1 个分组'
  }
  for (let gi = 0; gi < groups.length; gi += 1) {
    const samplers = groups[gi]?.samplers || []
    if (!samplers.length) {
      return `分组 ${gi + 1} 内至少需要 1 个采样器`
    }
    for (let si = 0; si < samplers.length; si += 1) {
      if (!(samplers[si].url || '').trim()) {
        return `分组 ${gi + 1} 采样器 ${si + 1} 的 URL 不能为空`
      }
      const extractors = samplers[si].extractors || []
      for (let ei = 0; ei < extractors.length; ei += 1) {
        const ex = extractors[ei]
        const hasAny = (ex.refName || '').trim() || (ex.expression || '').trim() || (ex.rightBoundary || '').trim()
        if (!hasAny) {
          continue
        }
        if (!(ex.refName || '').trim()) {
          return `分组 ${gi + 1} 采样器 ${si + 1} 提取器 ${ei + 1} 的引用名不能为空`
        }
        if (!(ex.expression || '').trim()) {
          return `分组 ${gi + 1} 采样器 ${si + 1} 提取器 ${ex.refName} 的提取表达式不能为空`
        }
        if (ex.type === 'BOUNDARY' && !(ex.rightBoundary || '').trim()) {
          return `分组 ${gi + 1} 采样器 ${si + 1} 边界提取器 ${ex.refName} 的右边界不能为空`
        }
      }
    }
  }
  return null
}

/**
 * 清洗单个采样器用于提交：过滤空动态行、补齐默认值（含 ignoreFirstLine）
 * @param {Object} sampler 原始采样器
 * @returns {Object} 清洗后的采样器
 */
function sanitizeSampler(sampler) {
  return {
    name: (sampler.name || '').trim() || (sampler.url || '').trim(),
    method: sampler.method || 'GET',
    url: (sampler.url || '').trim(),
    headers: (sampler.headers || [])
      .filter((header) => (header.k || '').trim())
      .map((header) => ({ k: header.k.trim(), v: header.v })),
    body: sampler.body || '',
    assertions: (sampler.assertions || [])
      .filter((assertion) => (assertion.expect || '').trim())
      .map((assertion) => ({ type: assertion.type, expect: assertion.expect.trim() })),
    extractors: (sampler.extractors || [])
      .filter((ex) => (ex.refName || '').trim() && (ex.expression || '').trim())
      .map((ex) => ({
        type: ex.type || 'JSON',
        refName: ex.refName.trim(),
        expression: ex.expression.trim(),
        rightBoundary: (ex.rightBoundary || '').trim(),
        source: ex.source || 'RESPONSE_BODY',
        template: (ex.template || '').trim() || '$1$',
        matchNumber: ex.matchNumber == null ? 1 : ex.matchNumber,
        defaultValue: (ex.defaultValue || '').trim() || 'NOT_FOUND'
      })),
    csvRefs: (sampler.csvRefs || [])
      .filter((csvRef) => csvRef.fileId)
      .map((csvRef) => ({
        fileId: csvRef.fileId,
        varNames: (csvRef.varNames || '').trim(),
        delimiter: csvRef.delimiter || ',',
        recycle: csvRef.recycle !== false,
        shareMode: csvRef.shareMode || 'shareMode.all',
        ignoreFirstLine: csvRef.ignoreFirstLine === true
      }))
  }
}

/**
 * 清洗表单定义用于提交：输出 groups 编排结构（剔除 _key 等内部字段），避免提交冗余字段
 * @param {Object} def 表单定义
 * @returns {Object} 清洗后的 formDef（threadGroupName/thinkTimeMs/groups）
 */
export function sanitizeFormDef(def) {
  return {
    threadGroupName: (def?.threadGroupName || '').trim() || '压测线程组',
    thinkTimeMs: def?.thinkTimeMs || 0,
    groups: (def?.groups || []).map((group) => ({
      name: (group.name || '').trim() || `分组${group._key || ''}`,
      execution: group.execution === 'PARALLEL' ? 'PARALLEL' : 'SERIAL',
      samplers: (group.samplers || []).map(sanitizeSampler)
    }))
  }
}

</script>

<script setup>
import { reactive, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'

// 组件属性：v-model 绑定的 formDef、只读禁用开关、参数文件引用的文件下拉选项（全量文件，不按类型过滤）
const props = defineProps({
  modelValue: { type: Object, default: null },
  disabled: { type: Boolean, default: false },
  fileOptions: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:modelValue'])

/** 采样器/分组卡片 key 自增序号，保证增删时卡片稳定复用 */
let keySeq = 0

/**
 * 创建一个空白参数文件引用行（含「忽略首行」默认关闭）
 * @returns {Object} 参数文件引用对象
 */
function createEmptyCsvRef() {
  return {
    fileId: null,
    varNames: '',
    delimiter: ',',
    recycle: true,
    shareMode: 'shareMode.all',
    ignoreFirstLine: false
  }
}

/**
 * 创建一个空白参数提取行（默认 JSON 提取、来源响应体）
 * @returns {Object} 参数提取器对象
 */
function createEmptyExtractor() {
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
 * 提取器类型/来源联动：切到 JSON 且来源为 URL 时重置为响应体（JMeter JSON 提取器不支持 URL 来源）
 * @param {Object} ex 提取器对象
 */
function handleExtractorFieldChange(ex) {
  if (ex.type === 'JSON' && ex.source === 'URL') {
    ex.source = 'RESPONSE_BODY'
  }
}

/**
 * 创建一个空白采样器对象（含各动态行数组）
 * @returns {Object} 采样器对象
 */
function createEmptySampler() {
  keySeq += 1
  return {
    _key: keySeq,
    name: '',
    method: 'GET',
    url: '',
    headers: [],
    body: '',
    assertions: [],
    extractors: [],
    csvRefs: []
  }
}

/**
 * 创建一个空白分组（默认组内串行，预置 1 个空采样器）
 * @returns {Object} 分组对象
 */
function createEmptyGroup() {
  keySeq += 1
  return {
    _key: keySeq,
    name: '',
    execution: 'SERIAL',
    samplers: [createEmptySampler()]
  }
}

/**
 * 规整采样器：补齐各动态行数组与默认方法
 * @param {Object} sampler 原始采样器
 * @returns {Object} 规整后的采样器
 */
function normalizeSampler(sampler = {}) {
  keySeq += 1
  return {
    _key: keySeq,
    name: sampler.name || '',
    method: sampler.method || 'GET',
    url: sampler.url || '',
    headers: (sampler.headers || []).map((header) => ({ k: header.k || '', v: header.v || '' })),
    body: sampler.body || '',
    assertions: (sampler.assertions || []).map((assertion) => ({
      type: assertion.type || 'TEXT',
      expect: assertion.expect || ''
    })),
    extractors: (sampler.extractors || []).map((ex) => ({ ...createEmptyExtractor(), ...ex })),
    csvRefs: (sampler.csvRefs || []).map((ref) => ({ ...createEmptyCsvRef(), ...ref }))
  }
}

/**
 * 规整分组：补齐执行方式与采样器列表
 * @param {Object} group 原始分组
 * @returns {Object} 规整后的分组
 */
function normalizeGroup(group = {}) {
  keySeq += 1
  return {
    _key: keySeq,
    name: group.name || '',
    execution: group.execution === 'PARALLEL' ? 'PARALLEL' : 'SERIAL',
    samplers: (group.samplers || []).map(normalizeSampler)
  }
}

/**
 * 规整表单定义：兼容旧数据（无 groups 时把顶层 samplers 包成单个串行组），
 * 无分组时预置一个空分组，保证初始编辑体验
 * @param {Object|null} val 原始 formDef
 * @returns {{threadGroupName:string,thinkTimeMs:number,groups:Array}} 规整后的表单定义
 */
function normalizeFormDef(val) {
  let groups
  if (Array.isArray(val?.groups) && val.groups.length) {
    groups = val.groups.map(normalizeGroup)
  } else if (Array.isArray(val?.samplers) && val.samplers.length) {
    groups = [normalizeGroup({ name: val.threadGroupName || '', execution: 'SERIAL', samplers: val.samplers })]
  } else {
    groups = [createEmptyGroup()]
  }
  return {
    threadGroupName: val?.threadGroupName || '',
    thinkTimeMs: val?.thinkTimeMs ?? 0,
    groups
  }
}

// 内部维护的可编辑表单定义（以 props.modelValue 初始化）
const def = reactive(normalizeFormDef(props.modelValue))

// 父组件整体替换 modelValue 时（如对话框重开），重新同步到内部状态
watch(
  () => props.modelValue,
  (val) => {
    if (val && val !== def) {
      Object.assign(def, normalizeFormDef(val))
    }
  }
)

// 内部任意编辑同步回父组件（v-model）
watch(
  def,
  () => {
    emit('update:modelValue', def)
  },
  { deep: true }
)

/**
 * 下拉目标分组展示名（未命名组按序号显示）
 * @param {number} index 分组下标
 * @param {Object} group 分组对象
 * @returns {string} 展示名
 */
function targetGroupLabel(index, group) {
  return `分组 ${index + 1}${group.name ? `（${group.name}）` : ''}`
}

/**
 * 新增一个空白分组
 */
function addGroup() {
  def.groups.push(createEmptyGroup())
}

/**
 * 删除分组（组内有采样器时需二次确认）
 * @param {number} index 分组下标
 */
async function removeGroup(index) {
  const group = def.groups[index]
  if (!group) {
    return
  }
  if (group.samplers.length) {
    await ElMessageBox.confirm(
      `分组「${group.name || index + 1}」内含 ${group.samplers.length} 个采样器，删除后不可恢复，是否继续？`,
      '删除分组',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  }
  def.groups.splice(index, 1)
}

/**
 * 在指定分组内新增一个空白采样器卡片
 * @param {number} gi 分组下标
 */
function addSampler(gi) {
  def.groups[gi].samplers.push(createEmptySampler())
}

/**
 * 删除指定分组内的采样器卡片
 * @param {number} gi 分组下标
 * @param {number} si 组内采样器下标
 */
function removeSampler(gi, si) {
  def.groups[gi].samplers.splice(si, 1)
}

/**
 * 组内上移/下移采样器卡片：与相邻位置交换
 * @param {number} gi   分组下标
 * @param {number} si   组内采样器下标
 * @param {number} offset 移动偏移量（-1 上移 / 1 下移）
 */
function moveSampler(gi, si, offset) {
  const list = def.groups[gi].samplers
  const target = si + offset
  if (target < 0 || target >= list.length) {
    return
  }
  ;[list[si], list[target]] = [list[target], list[si]]
}

/**
 * 把采样器移动到目标分组（从当前组移除后追加到目标组末尾）
 * @param {number} gi   当前分组下标
 * @param {number} si   组内采样器下标
 * @param {number} targetGi 目标分组下标
 */
function moveSamplerToGroup(gi, si, targetGi) {
  if (gi === targetGi || !def.groups[targetGi]) {
    return
  }
  const [sampler] = def.groups[gi].samplers.splice(si, 1)
  def.groups[targetGi].samplers.push(sampler)
}
</script>

<style scoped>
.unit-text {
  margin-left: 8px;
  font-size: 13px;
  color: #909399;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin: 6px 0 10px;
}

.group-tip {
  margin-bottom: 12px;
}

.group-card {
  margin-bottom: 16px;
  border: 1px solid #d9ecff;
}

.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.group-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.group-title {
  font-size: 14px;
  font-weight: 600;
  color: #409eff;
  white-space: nowrap;
}

.group-name-input {
  width: 180px;
}

.group-head-actions {
  display: flex;
  align-items: center;
}

.sampler-card {
  margin-bottom: 12px;
  border: 1px solid #ebeef5;
}

.sampler-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.sampler-title {
  font-size: 14px;
  font-weight: 600;
  color: #409eff;
}

.sampler-actions {
  display: flex;
  align-items: center;
}

.sampler-form {
  margin-top: 4px;
}

.sub-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin: 12px 0 8px;
}

.dyn-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.dyn-input {
  flex: 1;
}

.w-120 {
  width: 120px;
}

.w-140 {
  width: 140px;
}

.w-180 {
  width: 180px;
}

.csv-ref-row {
  padding: 8px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  margin-bottom: 8px;
}

/* 参数提取器行容器（两行式：类型/引用名/来源 + 表达式/模板/默认值） */
.extractor-row {
  padding: 8px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  margin-bottom: 8px;
}

/* 并行组内参数提取不支持提示 */
.extractor-tip {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}

.w-110 {
  width: 110px;
}

.w-130 {
  width: 130px;
}

.w-150 {
  width: 150px;
}

.switch-item {
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.label-text {
  font-size: 13px;
  color: #606266;
}
</style>
