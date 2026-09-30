package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.common.BizException;
import com.per.server.common.JmxThreadGroupParser;
import com.per.server.common.UserContext;
import com.per.server.dto.PageVO;
import com.per.server.dto.ScriptDetailVO;
import com.per.server.dto.ScriptFormRequest;
import com.per.server.dto.ScriptVersionCreateRequest;
import com.per.server.dto.ScriptVersionVO;
import com.per.server.dto.ScriptVO;
import com.per.server.dto.TaskCreateRequest;
import com.per.server.entity.DataFile;
import com.per.server.entity.Script;
import com.per.server.entity.ScriptVersion;
import com.per.server.mapper.DataFileMapper;
import com.per.server.mapper.ScriptMapper;
import com.per.server.mapper.ScriptVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 脚本中心服务：JMX 导入、表单生成 JMX、版本管理与查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptService {

    private final ScriptMapper scriptMapper;
    private final ScriptVersionMapper scriptVersionMapper;
    private final DataFileMapper dataFileMapper;
    private final FormScriptJmxBuilder jmxBuilder;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** JMX 中 CSVDataSet filename 属性提取正则（与 Agent 侧保持一致） */
    private static final java.util.regex.Pattern CSV_FILENAME_PATTERN =
            java.util.regex.Pattern.compile("<stringProp name=\"filename\">([^<]+)</stringProp>");

    /**
     * 分页查询脚本列表（keyword 模糊匹配 name，按 id 倒序）
     *
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @param keyword 关键词，模糊匹配脚本名
     * @return 脚本分页结果
     */
    public PageVO<ScriptVO> page(long page, long size, String keyword) {
        LambdaQueryWrapper<Script> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Script::getName, keyword);
        }
        wrapper.orderByDesc(Script::getId);
        Page<Script> result = scriptMapper.selectPage(new Page<>(page, size), wrapper);
        List<ScriptVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageVO.of(records, result.getTotal());
    }

    /**
     * 导入 JMX 脚本：读取上传的 JMX 文件内容保存为版本 1；
     * 自动从 JMX 解析 CSVDataSet 引用的参数文件并关联 data_file，防止漏传 fileIds 导致任务分发遗漏。
     *
     * @param jmxFile     上传的 JMX 文件
     * @param name        脚本名称
     * @param description 脚本描述
     * @param fileIds     关联文件 id（逗号分隔，可选，会与 JMX 自动解析的引用合并）
     * @return 创建后的脚本信息
     */
    @Transactional(rollbackFor = Exception.class)
    public ScriptVO importScript(MultipartFile jmxFile, String name, String description, String fileIds) {
        if (jmxFile == null || jmxFile.isEmpty()) {
            throw new BizException("jmxFile不能为空");
        }
        String jmxContent;
        try {
            jmxContent = new String(jmxFile.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("读取JMX文件失败：" + e.getMessage());
        }
        Script script = new Script();
        script.setName(name);
        script.setDescription(description);
        script.setType(Script.TYPE_IMPORTED);
        script.setLatestVersion(1);
        script.setCreateBy(currentUsername());
        scriptMapper.insert(script);
        // 自动解析 JMX 中 CSVDataSet 引用的参数文件，与显式 fileIds 合并
        String mergedFileIds = mergeFileIdsFromJmx(fileIds, jmxContent);
        insertVersion(script.getId(), 1, jmxContent, mergedFileIds, "导入初始版本");
        auditService.record("IMPORT_SCRIPT", "导入脚本 " + name);
        return toVO(script);
    }

    /**
     * 表单生成脚本：服务端渲染表单定义为标准 JMX 并保存为版本 1
     *
     * @param request 表单脚本创建请求
     * @return 创建后的脚本信息
     */
    @Transactional(rollbackFor = Exception.class)
    public ScriptVO createForm(ScriptFormRequest request) {
        validateFormDef(request.getFormDef());
        String jmx = renderJmx(request.getFormDef());
        String formDefJson = writeJson(request.getFormDef());
        Script script = new Script();
        script.setName(request.getName());
        script.setDescription(request.getDescription());
        script.setType(Script.TYPE_FORM);
        script.setFormDef(formDefJson);
        script.setLatestVersion(1);
        script.setCreateBy(currentUsername());
        scriptMapper.insert(script);
        insertVersion(script.getId(), 1, jmx, mergeFileIds(null, request.getFormDef()), "表单生成初始版本");
        auditService.record("CREATE_SCRIPT_FORM", "表单创建脚本 " + request.getName());
        return toVO(script);
    }

    /**
     * 查询脚本详情：基本信息 + 表单定义 + 版本摘要列表
     *
     * @param id 脚本ID
     * @return 脚本详情
     */
    public ScriptDetailVO detail(Long id) {
        Script script = requireScript(id);
        ScriptDetailVO vo = new ScriptDetailVO();
        copyBase(vo, script);
        if (StringUtils.hasText(script.getFormDef())) {
            vo.setFormDef(readTree(script.getFormDef()));
        }
        List<ScriptVersion> versions = scriptVersionMapper.selectList(new LambdaQueryWrapper<ScriptVersion>()
                .eq(ScriptVersion::getScriptId, id)
                .orderByDesc(ScriptVersion::getVersion));
        vo.setVersions(versions.stream().map(this::toVersionVO).toList());
        return vo;
    }

    /**
     * 获取指定版本的 JMX 文本
     *
     * @param id      脚本ID
     * @param version 版本号
     * @return JMX 文本
     */
    public String getJmx(Long id, Integer version) {
        requireScript(id);
        ScriptVersion sv = requireVersion(id, version);
        return sv.getJmxContent();
    }

    /**
     * 按脚本版本主键获取 JMX 内容（Agent 下载压测脚本端点使用）
     *
     * @param versionId 脚本版本表主键（test_task.script_version_id）
     * @return JMX 文本
     */
    public String getJmxByVersionId(Long versionId) {
        ScriptVersion sv = scriptVersionMapper.selectById(versionId);
        if (sv == null) {
            throw new BizException(4012, "脚本版本不存在: id=" + versionId);
        }
        return sv.getJmxContent();
    }

    /**
     * 新增脚本版本：jmxContent 与 formDef 二选一；formDef 方式会重渲染 JMX 并更新脚本的表单定义
     *
     * @param id      脚本ID
     * @param request 版本创建请求
     * @return 新版本摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public ScriptVersionVO addVersion(Long id, ScriptVersionCreateRequest request) {
        Script script = requireScript(id);
        boolean hasJmx = StringUtils.hasText(request.getJmxContent());
        boolean hasForm = request.getFormDef() != null;
        if (hasJmx == hasForm) {
            throw new BizException("jmxContent 与 formDef 必须二选一");
        }
        if (hasForm) {
            validateFormDef(request.getFormDef());
        }
        String jmx = hasJmx ? request.getJmxContent() : renderJmx(request.getFormDef());
        int nextVersion = script.getLatestVersion() + 1;
        // 表单方式自动并入 csvRefs 引用的文件；JMX 方式自动解析 CSVDataSet 引用的文件，
        // 防止调用方漏传 fileIds 导致任务分发遗漏参数文件
        String mergedFileIds = hasJmx
                ? mergeFileIdsFromJmx(request.getFileIds(), jmx)
                : mergeFileIds(request.getFileIds(), request.getFormDef());
        insertVersion(id, nextVersion, jmx, mergedFileIds, request.getRemark());
        if (hasForm) {
            Script update = new Script();
            update.setId(id);
            update.setFormDef(writeJson(request.getFormDef()));
            update.setLatestVersion(nextVersion);
            scriptMapper.updateById(update);
        } else {
            Script update = new Script();
            update.setId(id);
            update.setLatestVersion(nextVersion);
            scriptMapper.updateById(update);
        }
        auditService.record("UPDATE_SCRIPT_VERSION", "脚本 " + script.getName() + " 新增版本 v" + nextVersion);
        return toVersionVO(scriptVersionMapper.selectOne(new LambdaQueryWrapper<ScriptVersion>()
                .eq(ScriptVersion::getScriptId, id)
                .eq(ScriptVersion::getVersion, nextVersion)));
    }

    /**
     * 删除脚本及其全部版本，记录审计日志
     *
     * @param id 脚本ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Script script = requireScript(id);
        scriptVersionMapper.delete(new LambdaQueryWrapper<ScriptVersion>()
                .eq(ScriptVersion::getScriptId, id));
        scriptMapper.deleteById(id);
        auditService.record("DELETE_SCRIPT", "删除脚本 " + script.getName());
    }

    /**
     * 复制脚本：深拷贝基本信息与全部版本（FORM 脚本连同表单定义一起复制，版本号与备注保持一致），
     * 新名称自动追加「-副本」后缀（重名时追加序号），记录审计日志
     *
     * @param id 源脚本ID
     * @return 复制出的新脚本信息
     */
    @Transactional(rollbackFor = Exception.class)
    public ScriptVO copy(Long id) {
        Script source = requireScript(id);
        List<ScriptVersion> versions = scriptVersionMapper.selectList(new LambdaQueryWrapper<ScriptVersion>()
                .eq(ScriptVersion::getScriptId, id)
                .orderByAsc(ScriptVersion::getVersion));
        Script copy = new Script();
        copy.setName(nextCopyName(source.getName()));
        copy.setDescription(source.getDescription());
        copy.setType(source.getType());
        copy.setFormDef(source.getFormDef());
        copy.setLatestVersion(source.getLatestVersion());
        copy.setCreateBy(currentUsername());
        scriptMapper.insert(copy);
        for (ScriptVersion sv : versions) {
            insertVersion(copy.getId(), sv.getVersion(), sv.getJmxContent(), sv.getFileIds(), sv.getRemark());
        }
        auditService.record("COPY_SCRIPT", "复制脚本 " + source.getName() + " → " + copy.getName());
        return toVO(copy);
    }

    /**
     * 生成不与现有脚本重名的副本名称：原名-副本、原名-副本2、原名-副本3 ……
     * 追加后缀前先截断源名，保证总长不超过数据库 name 字段上限（VARCHAR(128)）
     *
     * @param sourceName 源脚本名称
     * @return 可用的副本名称
     */
    private String nextCopyName(String sourceName) {
        String base = sourceName.substring(0, Math.min(sourceName.length(), 120)) + "-副本";
        if (scriptMapper.selectCount(new LambdaQueryWrapper<Script>().eq(Script::getName, base)) == 0) {
            return base;
        }
        int seq = 2;
        while (scriptMapper.selectCount(new LambdaQueryWrapper<Script>().eq(Script::getName, base + seq)) > 0) {
            seq += 1;
        }
        return base + seq;
    }

    /**
     * 渲染表单定义为 JMX：收集 csvRefs 引用文件的原始名后调用渲染器
     *
     * @param formDef 表单场景定义
     * @return 渲染后的 JMX 文本
     */
    private String renderJmx(ScriptFormRequest.FormDef formDef) {
        return jmxBuilder.build(formDef, collectFileNames(formDef));
    }

    /**
     * 按压测模式重渲染表单脚本 JMX（任务创建时生成模式化快照用）：
     * 仅 FORM 类型脚本支持；读取脚本当前表单定义并按模式渲染线程组/定时器
     *
     * @param scriptId 脚本ID
     * @param mode     压测模式（CONCURRENT/FIXED_TPS/STEPPED）
     * @param config   模式参数
     * @return 按模式渲染后的 JMX 文本
     */
    public String renderModeJmx(Long scriptId, String mode, TaskCreateRequest.Config config) {
        Script script = requireScript(scriptId);
        if (!Script.TYPE_FORM.equals(script.getType()) || !StringUtils.hasText(script.getFormDef())) {
            throw new BizException("脚本不是表单类型，无法按模式渲染：scriptId=" + scriptId);
        }
        ScriptFormRequest.FormDef formDef;
        try {
            formDef = objectMapper.readValue(script.getFormDef(), ScriptFormRequest.FormDef.class);
        } catch (Exception e) {
            throw new BizException("表单定义解析失败：" + e.getMessage());
        }
        return jmxBuilder.buildWithMode(formDef, collectFileNames(formDef), mode, config);
    }

    /**
     * 收集表单定义中 csvRefs 引用文件的原始名映射（fileId → name）：
     * 遍历归一化后的编排分组（兼容旧顶层 samplers 结构）
     *
     * @param formDef 表单场景定义
     * @return 文件ID → 原始文件名映射
     */
    private Map<Long, String> collectFileNames(ScriptFormRequest.FormDef formDef) {
        Map<Long, String> fileNames = new HashMap<>();
        for (ScriptFormRequest.Sampler sampler : formSamplers(formDef)) {
            if (sampler.getCsvRefs() == null) {
                continue;
            }
            for (ScriptFormRequest.CsvRef csvRef : sampler.getCsvRefs()) {
                if (!fileNames.containsKey(csvRef.getFileId())) {
                    DataFile file = dataFileMapper.selectById(csvRef.getFileId());
                    if (file == null) {
                        throw new BizException("CSV引用的文件不存在：fileId=" + csvRef.getFileId());
                    }
                    fileNames.put(file.getId(), file.getName());
                }
            }
        }
        return fileNames;
    }

    /**
     * 获取表单定义的全部采样器（按归一化编排分组展开，兼容旧顶层 samplers 结构）
     *
     * @param formDef 表单场景定义
     * @return 全部采样器列表
     */
    private List<ScriptFormRequest.Sampler> formSamplers(ScriptFormRequest.FormDef formDef) {
        List<ScriptFormRequest.Sampler> samplers = new java.util.ArrayList<>();
        for (ScriptFormRequest.Group group : FormScriptJmxBuilder.normalizeGroups(formDef)) {
            samplers.addAll(group.getSamplers());
        }
        return samplers;
    }

    /**
     * 校验表单定义结构：groups 与 samplers 二选一（groups 优先），
     * 组数≤10、execution 仅 SERIAL/PARALLEL（渲染层归一化已兜底组内非空校验）
     *
     * @param formDef 表单场景定义
     */
    private void validateFormDef(ScriptFormRequest.FormDef formDef) {
        boolean hasGroups = formDef.getGroups() != null && !formDef.getGroups().isEmpty();
        boolean hasSamplers = formDef.getSamplers() != null && !formDef.getSamplers().isEmpty();
        if (!hasGroups && !hasSamplers) {
            throw new BizException("samplers不能为空");
        }
        if (hasGroups) {
            if (formDef.getGroups().size() > 10) {
                throw new BizException("编排分组数量不能超过 10");
            }
            for (ScriptFormRequest.Group group : formDef.getGroups()) {
                String execution = group.getExecution();
                if (StringUtils.hasText(execution) && !"SERIAL".equalsIgnoreCase(execution)
                        && !"PARALLEL".equalsIgnoreCase(execution)) {
                    throw new BizException("分组执行方式仅支持 SERIAL/PARALLEL：" + execution);
                }
            }
        }
    }

    /**
     * 插入一条脚本版本记录
     *
     * @param scriptId   脚本ID
     * @param version    版本号
     * @param jmxContent JMX 内容
     * @param fileIds    关联文件 id（逗号分隔，可为空）
     * @param remark     版本备注
     */
    private void insertVersion(Long scriptId, int version, String jmxContent, String fileIds, String remark) {
        ScriptVersion sv = new ScriptVersion();
        sv.setScriptId(scriptId);
        sv.setVersion(version);
        sv.setJmxContent(jmxContent);
        sv.setFileIds(fileIds);
        sv.setRemark(remark);
        sv.setCreateBy(currentUsername());
        scriptVersionMapper.insert(sv);
    }

    /**
     * 合并显式传入的关联文件与表单 csvRefs 引用的文件：
     * 表单脚本版本必须关联其引用的全部参数文件，否则创建任务时无法提示分发，
     * 会导致 JMeter 运行时找不到 CSV 文件（线程空转、零请求、任务"正常结束"但无结果）。
     *
     * @param fileIds 显式传入的关联文件 id（逗号分隔，可空）
     * @param formDef 表单场景定义（可空）
     * @return 合并去重后的 id 串（空返回 null）
     */
    private String mergeFileIds(String fileIds, ScriptFormRequest.FormDef formDef) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(fileIds)) {
            for (String s : fileIds.split(",")) {
                String trimmed = s.trim();
                if (StringUtils.hasText(trimmed)) {
                    ids.add(trimmed);
                }
            }
        }
        if (formDef != null) {
            // 遍历归一化编排分组（兼容旧顶层 samplers 结构）
            for (ScriptFormRequest.Sampler sampler : formSamplers(formDef)) {
                if (sampler.getCsvRefs() == null) {
                    continue;
                }
                for (ScriptFormRequest.CsvRef ref : sampler.getCsvRefs()) {
                    if (ref.getFileId() != null) {
                        ids.add(String.valueOf(ref.getFileId()));
                    }
                }
            }
        }
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    /**
     * 合并显式传入的关联文件与 JMX 中 CSVDataSet 引用的文件：
     * 导入脚本时自动从 JMX 提取 CSVDataSet 的 filename，按原始文件名匹配 data_file 表，
     * 防止调用方漏传 fileIds 导致任务分发遗漏参数文件，JMeter 运行时找不到 CSV（线程空转、零请求）。
     *
     * @param fileIds    显式传入的关联文件 id（逗号分隔，可空）
     * @param jmxContent JMX 脚本内容
     * @return 合并去重后的 id 串（空返回 null）
     */
    private String mergeFileIdsFromJmx(String fileIds, String jmxContent) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(fileIds)) {
            for (String s : fileIds.split(",")) {
                String trimmed = s.trim();
                if (StringUtils.hasText(trimmed)) {
                    ids.add(trimmed);
                }
            }
        }
        if (StringUtils.hasText(jmxContent)) {
            java.util.regex.Matcher matcher = CSV_FILENAME_PATTERN.matcher(jmxContent);
            while (matcher.find()) {
                String fileName = matcher.group(1).trim();
                if (fileName.isEmpty() || fileName.contains("${")) {
                    continue;
                }
                DataFile dataFile = dataFileMapper.selectOne(
                        new LambdaQueryWrapper<DataFile>().eq(DataFile::getName, fileName));
                if (dataFile != null) {
                    ids.add(String.valueOf(dataFile.getId()));
                } else {
                    log.warn("[Script] JMX 引用的参数文件「{}」未在 data_file 表中注册，" +
                            "请先上传该文件后再导入脚本，否则任务运行时将找不到该参数文件", fileName);
                }
            }
        }
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    /**
     * 校验脚本存在，不存在抛业务异常（供任务创建等跨服务场景复用）
     *
     * @param id 脚本ID
     * @return 脚本实体
     */
    public Script requireScript(Long id) {
        Script script = scriptMapper.selectById(id);
        if (script == null) {
            throw new BizException("脚本不存在");
        }
        return script;
    }

    /**
     * 校验脚本版本存在，不存在抛业务异常
     *
     * @param scriptId 脚本ID
     * @param version  版本号
     * @return 脚本版本实体
     */
    public ScriptVersion requireVersion(Long scriptId, Integer version) {
        ScriptVersion sv = scriptVersionMapper.selectOne(new LambdaQueryWrapper<ScriptVersion>()
                .eq(ScriptVersion::getScriptId, scriptId)
                .eq(ScriptVersion::getVersion, version));
        if (sv == null) {
            throw new BizException("脚本版本不存在");
        }
        return sv;
    }

    /**
     * 获取当前登录用户名（未登录时返回 null）
     *
     * @return 当前用户名
     */
    private String currentUsername() {
        return UserContext.get() == null ? null : UserContext.get().getUsername();
    }

    /**
     * 对象序列化为 JSON 字符串（失败抛业务异常）
     *
     * @param value 任意可序列化对象
     * @return JSON 字符串
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException("JSON序列化失败：" + e.getMessage());
        }
    }

    /**
     * JSON 字符串解析为树节点（失败抛业务异常）
     *
     * @param json JSON 字符串
     * @return 解析后的 JsonNode
     */
    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON解析失败：{}", json, e);
            return null;
        }
    }

    /**
     * 脚本实体转摘要视图对象
     *
     * @param script 脚本实体
     * @return 脚本摘要视图对象
     */
    private ScriptVO toVO(Script script) {
        ScriptVO vo = new ScriptVO();
        copyBase(vo, script);
        return vo;
    }

    /**
     * 复制脚本基本信息到视图对象
     *
     * @param vo     目标视图对象
     * @param script 脚本实体
     */
    private void copyBase(ScriptVO vo, Script script) {
        vo.setId(script.getId());
        vo.setName(script.getName());
        vo.setDescription(script.getDescription());
        vo.setType(script.getType());
        vo.setLatestVersion(script.getLatestVersion());
        vo.setCreateBy(script.getCreateBy());
        vo.setCreateTime(script.getCreateTime());
        vo.setUpdateTime(script.getUpdateTime());
    }

    /**
     * 脚本版本实体转摘要视图对象
     *
     * @param sv 脚本版本实体
     * @return 版本摘要视图对象
     */
    private ScriptVersionVO toVersionVO(ScriptVersion sv) {
        ScriptVersionVO vo = new ScriptVersionVO();
        vo.setId(sv.getId());
        vo.setVersion(sv.getVersion());
        vo.setRemark(sv.getRemark());
        vo.setFileIds(sv.getFileIds());
        // 解析 JMX 线程组名列表（导入脚本前端据此展开流量占比执行单元）
        vo.setThreadGroups(JmxThreadGroupParser.parse(sv.getJmxContent()).stream()
                .map(JmxThreadGroupParser.ThreadGroupInfo::name)
                .map(name -> StringUtils.hasText(name) ? name : "线程组")
                .toList());
        vo.setCreateBy(sv.getCreateBy());
        vo.setCreateTime(sv.getCreateTime());
        return vo;
    }

    /**
     * 供其他服务按 id 集合批量获取脚本名称（联查展示用）
     *
     * @param ids 脚本ID集合
     * @return 脚本ID → 名称映射
     */
    public Map<Long, String> nameMap(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return scriptMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Script::getId, Script::getName));
    }
}
