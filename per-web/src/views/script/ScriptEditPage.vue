<!--
  脚本独立编辑页（全屏路由页，替代原 960px 弹窗编辑）：
  - 路由 /scripts/new（新建）/scripts/:id/edit（编辑保存为新版本）
  - 左侧结构大纲（全局配置/线程组/各分组及接口，点击定位滚动，接口点击展开）
  - 右侧分区卡片：基本信息(新建)/版本信息(编辑) + 全局配置 + 线程组 + 分组卡片
  - 全局配置：协议/域名/端口统一目标环境（接口 URL 只填路径）+ 自定义变量（${name} 引用）
  - 采样器手风琴：紧凑列表行（方法徽标/名称/路径/摘要）+ 点击展开完整表单，一次只展开一个
  - 参数提取行内置「测试」按钮（打开 ExtractorTester 实时验证提取结果）
  - 离开页面未保存时二次确认，防止误操作丢失
-->
<template>
  <div class="edit-page">
    <!-- 顶部工具栏 -->
    <div class="edit-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" text @click="goBack" />
        <span class="script-name">{{ isEdit ? meta.name : '新建脚本' }}</span>
        <el-tag v-if="isEdit" size="small" effect="plain" class="ver-tag">
          保存为新版本 v{{ nextVersion }}
        </el-tag>
      </div>
      <el-button type="primary" :icon="Check" :loading="saving" @click="handleSave">
        {{ isEdit ? '保存新版本' : '创建脚本' }}
      </el-button>
    </div>

    <div class="edit-body">
      <!-- 左侧结构大纲 -->
      <aside class="outline">
        <div class="outline-title">脚本结构</div>
        <div
          class="outline-item"
          :class="{ active: activeSection === 'meta' }"
          @click="scrollToSection('meta')"
        >
          {{ isEdit ? '版本信息' : '基本信息' }}
        </div>
        <div
          class="outline-item"
          :class="{ active: activeSection === 'config' }"
          @click="scrollToSection('config')"
        >
          全局配置
          <span v-if="hasGlobalEnv" class="outline-badge env">环境</span>
        </div>
        <div
          class="outline-item"
          :class="{ active: activeSection === 'thread' }"
          @click="scrollToSection('thread')"
        >
          线程组设置
        </div>
        <template v-for="(group, gi) in def.groups" :key="group._key">
          <div
            class="outline-item group"
            :class="{ active: activeSection === 'g' + gi }"
            @click="scrollToSection('g' + gi)"
          >
            <span class="outline-gname">分组 {{ gi + 1 }}{{ group.name ? ' · ' + group.name : '' }}</span>
            <span class="outline-badge" :class="group.execution === 'PARALLEL' ? 'par' : 'ser'">
              {{ group.execution === 'PARALLEL' ? '并行' : '串行' }}
            </span>
          </div>
          <div
            v-for="sampler in group.samplers"
            :key="sampler._key"
            class="outline-item sampler"
            :class="{ active: expandedKey === sampler._key }"
            @click="expandSampler(sampler._key)"
          >
            <span class="m-chip" :class="methodClass(sampler.method)">{{ sampler.method }}</span>
            <span class="outline-sname">{{ briefName(sampler) }}</span>
          </div>
        </template>
      </aside>

      <!-- 右侧内容区（滚动容器） -->
      <div ref="contentRef" class="edit-content">
        <!-- 基本信息（新建）/ 版本信息（编辑） -->
        <section id="sec-meta" class="edit-card">
          <div class="card-head">
            <div>
              <div class="card-title">{{ isEdit ? '版本信息' : '基本信息' }}</div>
              <div class="card-desc">{{ isEdit ? '保存后生成新版本，原版本不受影响' : '脚本名称与描述' }}</div>
            </div>
          </div>
          <el-form label-width="80px" v-if="!isEdit">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="名称" required>
                  <el-input v-model="meta.name" placeholder="请输入脚本名称" maxlength="64" clearable />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="描述">
                  <el-input v-model="meta.description" placeholder="脚本描述（可选）" maxlength="200" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
          <el-form label-width="80px" v-else>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="版本备注">
                  <el-input v-model="meta.remark" placeholder="本次修改说明（可选）" maxlength="200" clearable />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="关联文件">
                  <el-select v-model="meta.fileIds" multiple clearable filterable class="w-full" placeholder="脚本依赖的参数/数据文件">
                    <el-option
                      v-for="item in fileOptions"
                      :key="item.id"
                      :label="`${item.name}（${item.fileType}）`"
                      :value="item.id"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </section>

        <!-- 全局配置 -->
        <section id="sec-config" class="edit-card">
          <div class="card-head">
            <div>
              <div class="card-title">全局配置</div>
              <div class="card-desc">
                统一目标环境与自定义变量：接口 URL 只填路径（如 /api/login），换环境只改这里，无需逐接口修改
              </div>
            </div>
          </div>
          <el-form label-width="80px">
            <el-row :gutter="12">
              <el-col :span="5">
                <el-form-item label="协议">
                  <el-select v-model="def.config.protocol">
                    <el-option label="http" value="http" />
                    <el-option label="https" value="https" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="域名">
                  <el-input
                    v-model="def.config.host"
                    placeholder="目标主机（如 192.168.1.10 或 api.example.com）"
                    clearable
                    class="mono"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="7">
                <el-form-item label="端口">
                  <el-input-number
                    v-model="def.config.port"
                    :min="1"
                    :max="65535"
                    :value-on-clear="null"
                    controls-position="right"
                    placeholder="默认端口留空"
                    class="w-full"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <div v-if="hasGlobalEnv" class="env-preview mono">
              目标环境：{{ globalEnvPreview }}（接口填相对路径时自动拼接）
            </div>

            <div class="sub-title">
              自定义变量
              <el-button link type="primary" size="small" :icon="Plus" @click="addVariable">
                添加变量
              </el-button>
            </div>
            <div class="var-hint">变量注入 JMeter 用户自定义变量，接口 URL / 请求头 / 请求体中以 ${变量名} 引用</div>
            <div v-for="(variable, vi) in def.config.variables" :key="vi" class="dyn-row">
              <el-input
                v-model="variable.name"
                placeholder="变量名（字母/数字/下划线）"
                class="w-200 mono"
                clearable
              />
              <el-input v-model="variable.value" placeholder="值（如 test001）" class="dyn-input mono" clearable />
              <el-button link type="danger" @click="def.config.variables.splice(vi, 1)">删除</el-button>
            </div>
          </el-form>
        </section>

        <!-- 线程组设置 -->
        <section id="sec-thread" class="edit-card">
          <div class="card-head">
            <div>
              <div class="card-title">线程组设置</div>
              <div class="card-desc">线程组名称与组内思考时间（并发数/持续时间在创建任务时配置）</div>
            </div>
          </div>
          <el-form label-width="80px">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="线程组名">
                  <el-input v-model="def.threadGroupName" placeholder="如：订单压测线程组" maxlength="64" clearable />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="思考时间">
                  <el-input-number v-model="def.thinkTimeMs" :min="0" :step="100" controls-position="right" />
                  <span class="unit-text">毫秒（0 表示不启用）</span>
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </section>

        <!-- 分组卡片 -->
        <section
          v-for="(group, gi) in def.groups"
          :id="'sec-g' + gi"
          :key="group._key"
          class="edit-card group-card"
        >
          <div class="card-head">
            <div class="group-head-left">
              <span class="group-index">{{ gi + 1 }}</span>
              <el-input v-model="group.name" placeholder="组名（如：浏览链路）" maxlength="32" class="group-name-input" />
              <el-radio-group v-model="group.execution" size="small">
                <el-radio-button value="SERIAL">组内串行</el-radio-button>
                <el-radio-button value="PARALLEL">组内并行</el-radio-button>
              </el-radio-group>
            </div>
            <div class="group-head-actions">
              <el-button size="small" :icon="Plus" @click="addSampler(gi)">添加接口</el-button>
              <el-button link type="danger" @click="removeGroup(gi)">删除分组</el-button>
            </div>
          </div>

          <el-alert
            v-if="gi === 0"
            type="info"
            :closable="false"
            class="group-tip"
            title="串行组：接口按顺序执行，可参数关联；并行组：每个接口独立线程组同时施压，任务中可单独设置流量占比"
          />

          <el-empty v-if="!group.samplers.length" description="该分组暂无接口，请添加" :image-size="48" />

          <!-- 接口手风琴块 -->
          <div
            v-for="(sampler, si) in group.samplers"
            :key="sampler._key"
            :data-sampler-key="sampler._key"
            class="sampler-block"
            :class="{ expanded: expandedKey === sampler._key }"
          >
            <!-- 紧凑列表行 -->
            <div class="sampler-brief" @click="toggleSampler(sampler._key)">
              <el-icon class="brief-caret" :class="{ open: expandedKey === sampler._key }"><ArrowRight /></el-icon>
              <span class="m-chip" :class="methodClass(sampler.method)">{{ sampler.method }}</span>
              <span class="brief-name">{{ briefName(sampler) }}</span>
              <span class="brief-url mono">{{ sampler.url || '未填路径' }}</span>
              <span class="brief-flags">
                <el-tag v-if="countOf(sampler.extractors)" size="small" effect="plain" type="success">提取 {{ countOf(sampler.extractors) }}</el-tag>
                <el-tag v-if="countOf(sampler.assertions)" size="small" effect="plain" type="warning">断言 {{ countOf(sampler.assertions) }}</el-tag>
                <el-tag v-if="countOf(sampler.csvRefs)" size="small" effect="plain" type="info">文件 {{ countOf(sampler.csvRefs) }}</el-tag>
              </span>
              <span class="brief-actions" @click.stop>
                <el-button link :disabled="si === 0" @click="moveSampler(gi, si, -1)">上移</el-button>
                <el-button link :disabled="si === group.samplers.length - 1" @click="moveSampler(gi, si, 1)">下移</el-button>
                <el-dropdown v-if="def.groups.length > 1" trigger="click" @command="(target) => moveSamplerToGroup(gi, si, target)">
                  <el-button link type="primary">移组</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="(target, ti) in def.groups" :key="target._key" :command="ti" :disabled="ti === gi">
                        分组 {{ ti + 1 }}{{ target.name ? '（' + target.name + '）' : '' }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button link type="danger" @click="removeSampler(gi, si)">删除</el-button>
              </span>
            </div>

            <!-- 展开的完整表单 -->
            <el-collapse-transition>
              <div v-show="expandedKey === sampler._key" class="sampler-detail">
                <el-form label-width="70px" label-position="left">
                  <el-row :gutter="12">
                    <el-col :span="8">
                      <el-form-item label="名称">
                        <el-input v-model="sampler.name" placeholder="接口名称" maxlength="64" clearable />
                      </el-form-item>
                    </el-col>
                    <el-col :span="4">
                      <el-form-item label="方法">
                        <el-select v-model="sampler.method">
                          <el-option v-for="m in METHOD_OPTIONS" :key="m" :label="m" :value="m" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="路径" required>
                        <el-input v-model="sampler.url" clearable :placeholder="urlPlaceholder">
                          <template v-if="urlPrefix(sampler)" #prepend>{{ urlPrefix(sampler) }}</template>
                        </el-input>
                      </el-form-item>
                    </el-col>
                  </el-row>

                  <!-- 请求头 -->
                  <div class="sub-title">
                    请求头
                    <el-button link type="primary" size="small" @click="sampler.headers.push({ k: '', v: '' })">+ 添加</el-button>
                  </div>
                  <div v-for="(header, hi) in sampler.headers" :key="hi" class="dyn-row">
                    <el-input v-model="header.k" placeholder="Header 名（如 Content-Type）" class="w-200 mono" clearable />
                    <el-input v-model="header.v" placeholder="Header 值（支持 ${变量}）" class="dyn-input mono" clearable />
                    <el-button link type="danger" @click="sampler.headers.splice(hi, 1)">删除</el-button>
                  </div>

                  <!-- 请求体 -->
                  <div class="sub-title">请求体（POST/PUT 原始文本）</div>
                  <el-input
                    v-model="sampler.body"
                    type="textarea"
                    :rows="3"
                    spellcheck="false"
                    class="mono-body"
                    placeholder='如 {"userId": ${userId}}（可选）'
                  />

                  <!-- 断言 -->
                  <div class="sub-title">
                    响应断言
                    <el-button link type="primary" size="small" @click="sampler.assertions.push({ type: 'TEXT', expect: '' })">+ 添加</el-button>
                  </div>
                  <div v-for="(assertion, ai) in sampler.assertions" :key="ai" class="dyn-row">
                    <el-select v-model="assertion.type" class="w-140">
                      <el-option label="文本包含" value="TEXT" />
                      <el-option label="响应码相等" value="CODE" />
                    </el-select>
                    <el-input v-model="assertion.expect" placeholder="期望值（如 success / 200）" class="dyn-input" clearable />
                    <el-button link type="danger" @click="sampler.assertions.splice(ai, 1)">删除</el-button>
                  </div>

                  <!-- 参数提取 -->
                  <div class="sub-title">
                    参数提取
                    <el-tooltip content="从本接口响应中提取值存入变量，串行组内后续接口以 ${引用名} 使用" placement="top">
                      <el-icon class="sub-help"><QuestionFilled /></el-icon>
                    </el-tooltip>
                    <el-button
                      v-if="group.execution !== 'PARALLEL'"
                      link type="primary" size="small"
                      @click="sampler.extractors.push(createEmptyExtractor())"
                    >+ 添加提取器</el-button>
                  </div>
                  <div v-if="group.execution === 'PARALLEL'" class="inline-tip">
                    并行组内接口各自独立线程执行，变量不互通，不支持参数提取；如需关联请改为串行组
                  </div>
                  <div v-for="ex in sampler.extractors" :key="ex._key" class="extractor-row">
                    <div class="dyn-row">
                      <el-select v-model="ex.type" class="w-110" @change="handleExtractorTypeChange(ex)">
                        <el-option v-for="t in EXTRACTOR_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
                      </el-select>
                      <el-input v-model="ex.refName" placeholder="引用名（后续接口 ${引用名} 使用）" class="dyn-input" clearable />
                      <el-select v-model="ex.source" class="w-130" @change="handleExtractorTypeChange(ex)">
                        <el-option
                          v-for="s in EXTRACTOR_SOURCE_OPTIONS"
                          :key="s.value"
                          :label="s.label"
                          :value="s.value"
                          :disabled="ex.type === 'JSON' && s.value === 'URL'"
                        />
                      </el-select>
                      <el-button link type="primary" @click="testerRef?.open(ex)">测试</el-button>
                      <el-button link type="danger" @click="removeExtractor(sampler, ex)">删除</el-button>
                    </div>
                    <div class="dyn-row">
                      <el-input
                        v-model="ex.expression"
                        :placeholder="expressionPlaceholder(ex.type)"
                        class="dyn-input mono"
                        clearable
                      />
                      <el-input v-if="ex.type === 'BOUNDARY'" v-model="ex.rightBoundary" placeholder="右边界" class="w-150 mono" clearable />
                      <el-input v-if="ex.type === 'REGEX'" v-model="ex.template" placeholder="模板 $1$" class="w-110 mono" clearable />
                      <el-input-number v-model="ex.matchNumber" :min="-1" :step="1" size="small" controls-position="right" class="w-120" />
                      <el-input v-model="ex.defaultValue" placeholder="未命中默认值" class="w-150" clearable />
                    </div>
                  </div>

                  <!-- 参数文件 -->
                  <div class="sub-title">
                    参数文件
                    <el-button link type="primary" size="small" @click="sampler.csvRefs.push(createEmptyCsvRef())">+ 添加</el-button>
                  </div>
                  <div v-for="(csvRef, ci) in sampler.csvRefs" :key="ci" class="csv-ref-row">
                    <div class="dyn-row">
                      <el-select v-model="csvRef.fileId" placeholder="选择参数文件（CSV/TXT）" filterable class="dyn-input">
                        <el-option v-for="item in fileOptions" :key="item.id" :label="item.name" :value="item.id" />
                      </el-select>
                      <el-input v-model="csvRef.varNames" placeholder="变量名，逗号分隔（如 userId,userName）" class="dyn-input" clearable />
                      <el-button link type="danger" @click="sampler.csvRefs.splice(ci, 1)">删除</el-button>
                    </div>
                    <div class="dyn-row">
                      <el-input v-model="csvRef.delimiter" placeholder="分隔符" class="w-110" />
                      <div class="switch-item"><span>循环读取</span><el-switch v-model="csvRef.recycle" /></div>
                      <div class="switch-item"><span>忽略首行</span><el-switch v-model="csvRef.ignoreFirstLine" /></div>
                      <el-select v-model="csvRef.shareMode" class="w-170">
                        <el-option label="所有线程共享" value="shareMode.all" />
                        <el-option label="线程组内共享" value="shareMode.group" />
                      </el-select>
                    </div>
                  </div>
                </el-form>
              </div>
            </el-collapse-transition>
          </div>
        </section>

        <!-- 添加分组 -->
        <div class="add-group-row">
          <el-button :icon="Plus" :disabled="def.groups.length >= 10" @click="addGroup">
            添加分组{{ def.groups.length >= 10 ? '（已达上限 10）' : '' }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 提取器测试面板（页面级单例，open 时传入提取器对象引用） -->
    <ExtractorTester ref="testerRef" />
  </div>
</template>

<script>
/**
 * HTTP 请求方法枚举（对齐 JMeter HTTP Sampler 支持的全部方法）
 */
export const METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS', 'TRACE']

/**
 * 提取器类型选项
 */
export const EXTRACTOR_TYPE_OPTIONS = [
  { value: 'JSON', label: 'JSON 提取' },
  { value: 'REGEX', label: '正则提取' },
  { value: 'BOUNDARY', label: '边界提取' }
]

/**
 * 提取来源选项（JSON 提取器不支持 URL，模板侧联动禁用）
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
 * 校验表单定义完整性：分组/采样器/URL（相对路径需全局域名）/提取器/变量名
 * @param {Object} def 表单定义（config/threadGroupName/thinkTimeMs/groups）
 * @returns {string|null} 错误文案，null 表示通过
 */
export function validateFormDef(def) {
  const groups = def?.groups || []
  if (!groups.length) {
    return '请至少添加 1 个分组'
  }
  const hasHost = !!(def?.config?.host || '').trim()
  for (let gi = 0; gi < groups.length; gi += 1) {
    const samplers = groups[gi]?.samplers || []
    if (!samplers.length) {
      return `分组 ${gi + 1} 内至少需要 1 个接口`
    }
    for (let si = 0; si < samplers.length; si += 1) {
      const sampler = samplers[si]
      const url = (sampler.url || '').trim()
      if (!url) {
        return `分组 ${gi + 1} 接口 ${si + 1} 的路径不能为空`
      }
      if (!url.includes('://') && !hasHost) {
        return `分组 ${gi + 1} 接口 ${si + 1} 未填完整地址，且全局配置未填写域名`
      }
      const extractors = sampler.extractors || []
      for (let ei = 0; ei < extractors.length; ei += 1) {
        const ex = extractors[ei]
        const hasAny = (ex.refName || '').trim() || (ex.expression || '').trim() || (ex.rightBoundary || '').trim()
        if (!hasAny) {
          continue
        }
        if (!(ex.refName || '').trim()) {
          return `分组 ${gi + 1} 接口 ${si + 1} 提取器 ${ei + 1} 的引用名不能为空`
        }
        if (!(ex.expression || '').trim()) {
          return `分组 ${gi + 1} 接口 ${si + 1} 提取器 ${ex.refName} 的提取表达式不能为空`
        }
        if (ex.type === 'BOUNDARY' && !(ex.rightBoundary || '').trim()) {
          return `分组 ${gi + 1} 接口 ${si + 1} 边界提取器 ${ex.refName} 的右边界不能为空`
        }
      }
    }
  }
  // 全局变量：名称格式与查重
  const variables = def?.config?.variables || []
  const seen = new Set()
  for (const variable of variables) {
    const name = (variable?.name || '').trim()
    if (!name) {
      continue
    }
    if (!/^[A-Za-z0-9_]+$/.test(name)) {
      return `全局变量「${name}」名称仅支持字母/数字/下划线`
    }
    if (seen.has(name)) {
      return `全局变量「${name}」重复定义`
    }
    seen.add(name)
  }
  return null
}

/**
 * 清洗单个采样器用于提交：过滤空动态行、补齐默认值
 * @param {Object} sampler 原始采样器
 * @returns {Object} 清洗后的采样器（剔除 _key 等内部字段）
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
 * 清洗表单定义用于提交：含全局配置（环境未填且无变量时 config 置空，保持旧结构兼容）
 * @param {Object} def 表单定义
 * @returns {Object} 清洗后的 formDef
 */
export function sanitizeFormDef(def) {
  const variables = (def?.config?.variables || [])
    .filter((v) => (v?.name || '').trim())
    .map((v) => ({ name: v.name.trim(), value: v.value || '' }))
  const host = (def?.config?.host || '').trim()
  const port = def?.config?.port || null
  const protocol = def?.config?.protocol || 'http'
  const config = host || variables.length
    ? { protocol, host, port, variables }
    : null
  return {
    config,
    threadGroupName: (def?.threadGroupName || '').trim() || '压测线程组',
    thinkTimeMs: def?.thinkTimeMs || 0,
    groups: (def?.groups || []).map((group, gi) => ({
      name: (group.name || '').trim() || `分组${gi + 1}`,
      execution: group.execution === 'PARALLEL' ? 'PARALLEL' : 'SERIAL',
      samplers: (group.samplers || []).map(sanitizeSampler)
    }))
  }
}
</script>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Check, Plus, QuestionFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ExtractorTester from '@/components/ExtractorTester.vue'
import { addVersion, createForm, detail } from '@/api/script'
import { page as pageFiles } from '@/api/file'

const route = useRoute()
const router = useRouter()

/** 编辑模式（/scripts/:id/edit），否则新建模式 */
const isEdit = computed(() => !!route.params.id)

/** 脚本元信息：新建（名称/描述），编辑（脚本名展示/版本备注/关联文件） */
const meta = reactive({
  name: '',
  description: '',
  remark: '',
  fileIds: [],
  latestVersion: 0
})

/** 编辑模式下的下一个版本号 */
const nextVersion = computed(() => (meta.latestVersion || 0) + 1)

/** 文件库下拉选项（CSV 引用与版本关联文件） */
const fileOptions = ref([])

/** 保存中状态 */
const saving = ref(false)

/** 提取器测试面板引用 */
const testerRef = ref(null)

/** 内容滚动容器（大纲滚动定位与可视区监听） */
const contentRef = ref(null)

/** 当前展开的采样器 key（手风琴：一次只展开一个） */
const expandedKey = ref(0)

/** 大纲当前高亮的分区标识 */
const activeSection = ref('meta')

/** 卡片 key 自增序号（分组/采样器/提取器稳定复用） */
let keySeq = 0

/**
 * 创建空白参数文件引用行
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
 * 创建空白提取器（默认 JSON 提取、响应体来源）
 * @returns {Object} 提取器对象
 */
function createEmptyExtractor() {
  keySeq += 1
  return {
    _key: keySeq,
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
 * 提取器类型/来源联动：JSON 不支持 URL 来源；切类型时重置默认值
 * @param {Object} ex 提取器对象
 */
function handleExtractorTypeChange(ex) {
  if (ex.type === 'JSON' && ex.source === 'URL') {
    ex.source = 'RESPONSE_BODY'
  }
}

/**
 * 删除提取器（删除后同步收起测试面板）
 * @param {Object} sampler 所属采样器
 * @param {Object} ex 提取器对象
 */
function removeExtractor(sampler, ex) {
  const index = sampler.extractors.indexOf(ex)
  if (index >= 0) {
    sampler.extractors.splice(index, 1)
  }
}

/**
 * 提取表达式输入提示（按类型切换）
 * @param {string} type 提取类型
 * @returns {string} placeholder 文案
 */
function expressionPlaceholder(type) {
  if (type === 'JSON') {
    return 'JSONPath（如 $.data.token），点「测试」可实时验证'
  }
  if (type === 'BOUNDARY') {
    return '左边界（提取左右边界之间的内容）'
  }
  return '正则（分组捕获，如 token="(\\w+)"）'
}

/**
 * 创建空白采样器对象
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
 * 创建空白分组（默认串行，预置 1 个空接口）
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
 * 规整采样器：补齐动态行数组与默认值
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
 * 规整全局配置：旧数据无 config 时给空环境 + 空变量表
 * @param {Object|null} config 原始全局配置
 * @returns {Object} 规整后的全局配置
 */
function normalizeConfig(config) {
  return {
    protocol: config?.protocol === 'https' ? 'https' : 'http',
    host: config?.host || '',
    port: config?.port ?? null,
    variables: (config?.variables || []).map((v) => ({ name: v?.name || '', value: v?.value || '' }))
  }
}

/**
 * 规整表单定义：兼容旧数据（无 groups 时包成单串行组）
 * @param {Object|null} val 原始 formDef
 * @returns {Object} 规整后的表单定义
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
  return reactive({
    config: normalizeConfig(val?.config),
    threadGroupName: val?.threadGroupName || '',
    thinkTimeMs: val?.thinkTimeMs ?? 0,
    groups
  })
}

/** 内部可编辑表单定义 */
const def = normalizeFormDef(null)

/** 是否配置了全局域名（决定接口 URL 是否可只填路径） */
const hasGlobalEnv = computed(() => !!(def.config.host || '').trim())

/** 全局环境预览串（协议://域名[:端口]） */
const globalEnvPreview = computed(() => {
  const host = (def.config.host || '').trim()
  if (!host) {
    return ''
  }
  const port = def.config.port ? ':' + def.config.port : ''
  return `${def.config.protocol}://${host}${port}`
})

/** 接口路径输入提示（有全局域名时引导填路径） */
const urlPlaceholder = computed(() =>
  hasGlobalEnv.value
    ? '/api/login?a=b（相对路径自动拼接全局环境）'
    : 'http://host:port/path?a=b 或填写全局配置后只填路径'
)

/**
 * 接口 URL 输入框前缀：有全局环境且当前 URL 为相对路径时显示拼接前缀
 * @param {Object} sampler 采样器对象
 * @returns {string} 前缀文本（空串不显示）
 */
function urlPrefix(sampler) {
  const url = (sampler.url || '').trim()
  if (!hasGlobalEnv.value || url.includes('://')) {
    return ''
  }
  const port = def.config.port ? ':' + def.config.port : ''
  return `${def.config.protocol}://${def.config.host.trim()}${port}`
}

/**
 * 接口摘要名：名称 → 未填路径时占位
 * @param {Object} sampler 采样器对象
 * @returns {string} 展示名
 */
function briefName(sampler) {
  return sampler.name || '未命名接口'
}

/**
 * 统计动态行有效条数（提取器/断言/文件徽标）
 * @param {Array} list 动态行数组
 * @returns {number} 有效条数
 */
function countOf(list) {
  return (list || []).length
}

/**
 * 方法徽标样式类（GET 绿 / POST 蓝 / PUT 橙 / DELETE 红）
 * @param {string} method HTTP 方法
 * @returns {string} 样式类名
 */
function methodClass(method) {
  const m = (method || 'GET').toUpperCase()
  if (m === 'GET') {
    return 'm-get'
  }
  if (m === 'POST') {
    return 'm-post'
  }
  if (m === 'PUT' || m === 'PATCH') {
    return 'm-put'
  }
  if (m === 'DELETE') {
    return 'm-delete'
  }
  return 'm-other'
}

/**
 * 添加全局变量行
 */
function addVariable() {
  def.config.variables.push({ name: '', value: '' })
}

/**
 * 添加分组（上限 10）
 */
function addGroup() {
  def.groups.push(createEmptyGroup())
}

/**
 * 删除分组（组内有接口时二次确认）
 * @param {number} index 分组下标
 */
async function removeGroup(index) {
  const group = def.groups[index]
  if (!group) {
    return
  }
  if (group.samplers.length) {
    try {
      await ElMessageBox.confirm(
        `分组「${group.name || index + 1}」内含 ${group.samplers.length} 个接口，删除后不可恢复，是否继续？`,
        '删除分组',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  def.groups.splice(index, 1)
}

/**
 * 在分组内新增接口并自动展开
 * @param {number} gi 分组下标
 */
function addSampler(gi) {
  const sampler = createEmptySampler()
  def.groups[gi].samplers.push(sampler)
  expandedKey.value = sampler._key
}

/**
 * 删除接口（含提取器/断言等内容时二次确认）
 * @param {number} gi 分组下标
 * @param {number} si 组内接口下标
 */
async function removeSampler(gi, si) {
  const sampler = def.groups[gi]?.samplers[si]
  if (!sampler) {
    return
  }
  if (sampler.extractors?.length || sampler.assertions?.length) {
    try {
      await ElMessageBox.confirm(
        `接口「${briefName(sampler)}」的配置删除后不可恢复，是否继续？`,
        '删除接口',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  def.groups[gi].samplers.splice(si, 1)
}

/**
 * 组内上移/下移接口
 * @param {number} gi     分组下标
 * @param {number} si     组内接口下标
 * @param {number} offset 移动偏移（-1/1）
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
 * 接口移动到目标分组
 * @param {number} gi        当前分组下标
 * @param {number} si        组内接口下标
 * @param {number} targetGi  目标分组下标
 */
function moveSamplerToGroup(gi, si, targetGi) {
  if (gi === targetGi || !def.groups[targetGi]) {
    return
  }
  const [sampler] = def.groups[gi].samplers.splice(si, 1)
  def.groups[targetGi].samplers.push(sampler)
}

/**
 * 切换接口展开/收起（手风琴：展开另一个时收起当前）
 * @param {number} key 采样器 _key
 */
function toggleSampler(key) {
  expandedKey.value = expandedKey.value === key ? 0 : key
}

/**
 * 大纲接口点击：展开并滚动到对应块
 * @param {number} key 采样器 _key
 */
function expandSampler(key) {
  expandedKey.value = key
  nextTick(() => {
    document.querySelector(`[data-sampler-key="${key}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

/**
 * 大纲分区点击：平滑滚动到对应卡片
 * @param {string} id 分区标识（meta/config/thread/g{n}）
 */
function scrollToSection(id) {
  activeSection.value = id
  document.getElementById('sec-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/**
 * 加载文件库下拉（前 100 条）
 */
async function loadFileOptions() {
  try {
    const res = await pageFiles({ page: 1, size: 100 })
    fileOptions.value = res.data?.records || []
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  }
}

/**
 * 编辑模式加载脚本详情：回填元信息与表单定义
 */
async function loadScript() {
  const id = route.params.id
  try {
    const res = await detail(id)
    const data = res.data || {}
    meta.name = data.name || ''
    meta.latestVersion = data.latestVersion || 0
    const latest = (data.versions || [])[0]
    if (latest?.fileIds) {
      meta.fileIds = String(latest.fileIds)
        .split(',')
        .map((s) => Number(s.trim()))
        .filter((n) => n > 0)
    }
    const normalized = normalizeFormDef(data.formDef)
    Object.assign(def, normalized)
    keySeq += 1
    expandedKey.value = 0
  } catch {
    // 错误提示已由 http 拦截器统一弹出
    router.replace('/scripts')
  }
}

/**
 * 保存：新建走 createForm，编辑走 addVersion（生成新版本）
 */
async function handleSave() {
  if (!isEdit.value && !(meta.name || '').trim()) {
    ElMessage.warning('请输入脚本名称')
    return
  }
  const invalid = validateFormDef(def)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await addVersion(route.params.id, {
        formDef: sanitizeFormDef(def),
        remark: (meta.remark || '').trim(),
        fileIds: meta.fileIds.length ? meta.fileIds.join(',') : ''
      })
      ElMessage.success(`已保存为新版本 v${nextVersion.value}`)
      saved.value = true
      router.replace(`/scripts/${route.params.id}`)
    } else {
      const res = await createForm({
        name: meta.name.trim(),
        description: (meta.description || '').trim(),
        formDef: sanitizeFormDef(def)
      })
      ElMessage.success('脚本创建成功')
      saved.value = true
      router.replace(`/scripts/${res.data?.id || ''}`)
    }
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

/** 已保存标记（离开页面确认用） */
const saved = ref(false)

/**
 * 返回上一页：未保存修改时二次确认
 */
async function goBack() {
  if (!saved.value) {
    try {
      await ElMessageBox.confirm('当前编辑内容尚未保存，离开后将丢失，是否继续？', '离开确认', {
        type: 'warning',
        confirmButtonText: '离开',
        cancelButtonText: '继续编辑'
      })
    } catch {
      return
    }
  }
  if (window.history.length > 1) {
    router.back()
  } else {
    router.replace(isEdit.value ? `/scripts/${route.params.id}` : '/scripts')
  }
}

/** 可视分区监听器（大纲高亮跟随滚动） */
let sectionObserver = null

/**
 * 挂载可视区监听：哪个卡片在可视范围内即高亮对应大纲项
 */
function setupSectionObserver() {
  if (!contentRef.value || typeof IntersectionObserver === 'undefined') {
    return
  }
  sectionObserver = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const id = entry.target.id.replace('sec-', '')
          if (id.startsWith('g')) {
            activeSection.value = id
          } else {
            activeSection.value = id
          }
        }
      }
    },
    { root: contentRef.value, rootMargin: '-15% 0px -70% 0px', threshold: 0 }
  )
  document.querySelectorAll('.edit-content section[id]').forEach((el) => sectionObserver.observe(el))
}

// 表单定义变化（增删分组/接口）后重新挂载监听的卡片
watch(
  () => def.groups.length,
  () => {
    nextTick(() => {
      sectionObserver?.disconnect()
      setupSectionObserver()
    })
  }
)

// 页面挂载：加载文件下拉；编辑模式加载脚本详情
onMounted(async () => {
  await loadFileOptions()
  if (isEdit.value) {
    await loadScript()
  }
  await nextTick()
  setupSectionObserver()
})

// 卸载前断开监听
onBeforeUnmount(() => {
  sectionObserver?.disconnect()
})
</script>

<style scoped>
.edit-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  margin: -20px -24px;
  background-color: var(--pp-bg);
}

/* ===== 顶部工具栏 ===== */
.edit-header {
  height: 54px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background-color: var(--pp-surface);
  border-bottom: 1px solid var(--pp-border);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.script-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--pp-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ver-tag {
  flex-shrink: 0;
}

/* ===== 主体：左大纲 + 右内容 ===== */
.edit-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.outline {
  width: 248px;
  flex-shrink: 0;
  border-right: 1px solid var(--pp-border);
  background-color: var(--pp-surface);
  padding: 14px 10px;
  overflow-y: auto;
}
.outline-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--pp-text-placeholder);
  padding: 0 10px 8px;
  letter-spacing: 0.5px;
}
.outline-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border-radius: 7px;
  font-size: 13px;
  color: var(--pp-text-regular);
  cursor: pointer;
  transition: background-color 0.12s ease, color 0.12s ease;
}
.outline-item:hover {
  background-color: var(--pp-bg);
}
.outline-item.active {
  background-color: var(--el-color-primary-light-9);
  color: var(--pp-primary);
  font-weight: 500;
}
.outline-item.group {
  margin-top: 6px;
  font-weight: 500;
}
.outline-item.sampler {
  padding-left: 24px;
  color: var(--pp-text-secondary);
  gap: 8px;
}
.outline-gname {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.outline-sname {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
}
.outline-badge {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
}
.outline-badge.env {
  background-color: var(--el-color-primary-light-8);
  color: var(--pp-primary);
}
.outline-badge.ser {
  background-color: var(--el-color-success-light-9);
  color: var(--pp-success);
}
.outline-badge.par {
  background-color: var(--el-color-warning-light-9);
  color: var(--pp-warning);
}

/* 方法徽标（大纲与接口列表共用） */
.m-chip {
  flex-shrink: 0;
  font-family: var(--pp-font-mono);
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 5px;
  letter-spacing: 0.3px;
}
.m-get {
  background-color: var(--el-color-success-light-9);
  color: var(--pp-success);
}
.m-post {
  background-color: var(--el-color-primary-light-9);
  color: var(--pp-primary);
}
.m-put {
  background-color: var(--el-color-warning-light-9);
  color: var(--pp-warning);
}
.m-delete {
  background-color: var(--el-color-danger-light-9);
  color: var(--pp-danger);
}
.m-other {
  background-color: var(--pp-bg);
  color: var(--pp-text-secondary);
}

/* ===== 内容区 ===== */
.edit-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 20px 24px 40px;
  scroll-behavior: smooth;
}

.edit-card {
  background-color: var(--pp-surface);
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius);
  box-shadow: var(--pp-shadow);
  padding: 18px 20px;
  margin-bottom: 16px;
  scroll-margin-top: 8px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--pp-text-primary);
}
.card-desc {
  font-size: 12px;
  color: var(--pp-text-secondary);
  margin-top: 3px;
  line-height: 1.5;
}

/* 环境预览条 */
.env-preview {
  font-size: 12px;
  color: var(--pp-primary);
  background-color: var(--el-color-primary-light-9);
  border-radius: 6px;
  padding: 7px 10px;
  margin-bottom: 4px;
}

.var-hint {
  font-size: 12px;
  color: var(--pp-text-placeholder);
  margin-bottom: 8px;
}

/* 分组卡片头 */
.group-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.group-index {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background-color: var(--pp-bg);
  border: 1px solid var(--pp-border-strong);
  color: var(--pp-text-secondary);
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.group-name-input {
  width: 200px;
}
.group-head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.group-tip {
  margin-bottom: 12px;
}

/* ===== 接口手风琴 ===== */
.sampler-block {
  border: 1px solid var(--pp-border);
  border-radius: var(--pp-radius-sm);
  margin-bottom: 10px;
  overflow: hidden;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  background-color: var(--pp-surface);
}
.sampler-block.expanded {
  border-color: var(--el-color-primary-light-5);
  box-shadow: var(--pp-shadow-md);
}

.sampler-brief {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
  transition: background-color 0.12s ease;
}
.sampler-brief:hover {
  background-color: var(--pp-surface-2);
}
.brief-caret {
  font-size: 12px;
  color: var(--pp-text-placeholder);
  transition: transform 0.18s ease;
  flex-shrink: 0;
}
.brief-caret.open {
  transform: rotate(90deg);
}
.brief-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--pp-text-primary);
  white-space: nowrap;
  flex-shrink: 0;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brief-url {
  flex: 1;
  min-width: 80px;
  font-size: 12px;
  color: var(--pp-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brief-flags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.brief-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.sampler-brief:hover .brief-actions,
.sampler-block.expanded .brief-actions {
  opacity: 1;
}

.sampler-detail {
  border-top: 1px solid var(--pp-border);
  padding: 16px 16px 12px;
  background-color: var(--pp-surface);
}

/* ===== 表单内部 ===== */
.sub-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--pp-text-regular);
  margin: 14px 0 8px;
}
.sub-help {
  font-size: 13px;
  color: var(--pp-text-placeholder);
  cursor: help;
  margin-right: 6px;
}
.sub-title .el-button {
  margin-left: auto;
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
  min-width: 160px;
}
.w-110 { width: 110px; }
.w-120 { width: 120px; }
.w-130 { width: 130px; }
.w-140 { width: 140px; }
.w-150 { width: 150px; }
.w-170 { width: 170px; }
.w-200 { width: 200px; }
.w-full { width: 100%; }

.unit-text {
  margin-left: 8px;
  font-size: 12px;
  color: var(--pp-text-secondary);
}

.mono-body :deep(textarea) {
  font-family: var(--pp-font-mono);
  font-size: 12px;
}

.extractor-row {
  padding: 10px;
  border: 1px dashed var(--pp-border-strong);
  border-radius: var(--pp-radius-sm);
  margin-bottom: 8px;
}

.inline-tip {
  font-size: 12px;
  color: var(--pp-text-placeholder);
  margin-bottom: 8px;
}

.csv-ref-row {
  padding: 10px;
  border: 1px dashed var(--pp-border-strong);
  border-radius: var(--pp-radius-sm);
  margin-bottom: 8px;
}

.switch-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--pp-text-secondary);
  white-space: nowrap;
}

.add-group-row {
  display: flex;
  justify-content: center;
  padding: 4px 0 20px;
}
</style>
