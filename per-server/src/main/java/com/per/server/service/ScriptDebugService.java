package com.per.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.common.BizException;
import com.per.server.dto.ScriptDebugVO;
import com.per.server.dto.ScriptFormRequest;
import com.per.server.entity.DataFile;
import com.per.server.mapper.DataFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本调试服务：不落库、不依赖压测节点，server 直接按表单定义逐接口顺序发起一次真实请求，
 * 展示每个接口的完整请求/响应/断言/提取结果，用于编辑页「一键调试」快速自检接口可用性。
 * <p>变量语义与 JMeter 运行时对齐：全局自定义变量先入变量池，串行链路中提取器命中的值
 * 以 ${refName} 传递给后续接口（URL/请求头/请求体均支持替换）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptDebugService {

    /** 数据文件查询（参数文件 CSV 首行数据加载用） */
    private final DataFileMapper dataFileMapper;

    /** JSON 解析器（只读场景，ObjectMapper 线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 请求/响应体最大保留字符数（超出截断，防止大响应拖垮前端） */
    private static final int MAX_BODY_CHARS = 64 * 1024;

    /** 单请求超时 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** JMeter 风格变量占位符：${name} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** 正则模板占位符：$1$ / $2$ 等（取对应捕获分组） */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$(\\d+)\\$");

    /** 允许携带请求体的方法（GET/HEAD 不允许带体） */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE", "TRACE");

    /** 共享 HTTP 客户端：跟随重定向（与 JMeter 默认 followRedirects=true 一致） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 调试整份表单定义：按分组顺序逐接口请求一次，串行链路提取的变量向后传递
     *
     * @param def 表单定义（编辑器当前内容，未保存也可调试）
     * @return 逐接口调试明细
     */
    public ScriptDebugVO debug(ScriptFormRequest.FormDef def) {
        List<ScriptFormRequest.Group> groups = resolveGroups(def);
        Map<String, String> vars = initVariables(def);
        ScriptFormRequest.Config cfg = def == null ? null : def.getConfig();

        List<ScriptDebugVO.Item> items = new ArrayList<>();
        for (int gi = 0; gi < groups.size(); gi++) {
            ScriptFormRequest.Group group = groups.get(gi);
            List<ScriptFormRequest.Sampler> samplers = group.getSamplers() == null
                    ? List.of() : group.getSamplers();
            for (int si = 0; si < samplers.size(); si++) {
                items.add(debugSampler(group, gi, samplers.get(si), si, cfg, vars));
            }
        }
        ScriptDebugVO vo = new ScriptDebugVO();
        vo.setItems(items);
        return vo;
    }

    /**
     * 解析分组列表：优先 groups 新结构，旧数据 samplers 包成单串行组，两者皆空时报错
     *
     * @param def 表单定义
     * @return 分组列表
     */
    private List<ScriptFormRequest.Group> resolveGroups(ScriptFormRequest.FormDef def) {
        if (def == null) {
            throw new BizException("表单定义为空，无法调试");
        }
        if (def.getGroups() != null && !def.getGroups().isEmpty()) {
            return def.getGroups();
        }
        if (def.getSamplers() != null && !def.getSamplers().isEmpty()) {
            ScriptFormRequest.Group group = new ScriptFormRequest.Group();
            group.setName(def.getThreadGroupName());
            group.setExecution("SERIAL");
            group.setSamplers(def.getSamplers());
            return List.of(group);
        }
        throw new BizException("脚本内没有接口，请先添加接口再调试");
    }

    /**
     * 初始化变量池：全局自定义变量入池（变量值支持引用更早定义的变量）
     *
     * @param def 表单定义
     * @return 变量池
     */
    private Map<String, String> initVariables(ScriptFormRequest.FormDef def) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (def == null || def.getConfig() == null || def.getConfig().getVariables() == null) {
            return vars;
        }
        for (ScriptFormRequest.Variable variable : def.getConfig().getVariables()) {
            if (variable != null && StringUtils.hasText(variable.getName())) {
                vars.put(variable.getName().trim(), resolveVars(variable.getValue(), vars));
            }
        }
        return vars;
    }

    /**
     * 调试单个接口：变量替换 → 环境拼接 → 发请求 → 断言 → 提取（提取值回写变量池）
     *
     * @param group    所属分组
     * @param gi       分组序号
     * @param sampler  采样器定义
     * @param si       组内序号
     * @param cfg      全局配置
     * @param vars     变量池（跨接口传递，本方法内会写入提取结果）
     * @return 单接口调试明细
     */
    private ScriptDebugVO.Item debugSampler(ScriptFormRequest.Group group, int gi,
                                            ScriptFormRequest.Sampler sampler, int si,
                                            ScriptFormRequest.Config cfg, Map<String, String> vars) {
        ScriptDebugVO.Item item = new ScriptDebugVO.Item();
        item.setGroupIndex(gi);
        item.setGroupName(group.getName());
        item.setExecution(group.getExecution());
        item.setSamplerIndex(si);
        item.setName(StringUtils.hasText(sampler.getName()) ? sampler.getName() : sampler.getUrl());
        item.setMethod(methodOf(sampler.getMethod()));

        // 0) 参数文件变量加载：取每个 CSV 引用的首行数据入池（模拟 JMeter 单线程取首行），
        //    URL/请求头/请求体中的 ${列名} 均可引用；文件缺失时跳过（变量未定义将在下方 URL 校验中提示）
        loadCsvVars(sampler, vars);

        // 1) URL：变量替换 + 相对路径补全全局环境（规则与 FormScriptJmxBuilder 一致）
        String url = resolveVars(trimToEmpty(sampler.getUrl()), vars);
        if (!url.contains("://")) {
            if (cfg == null || !StringUtils.hasText(cfg.getHost())) {
                item.setError("接口为相对路径，但基本信息未填写目标域名（或接口直接填完整地址）");
                return item;
            }
            url = buildAbsoluteUrl(cfg, url);
        }
        // 变量替换后 URL 仍残留 ${xxx} 即为未定义变量（参数文件缺失/变量名写错），
        // 提前拦截并给出可用变量清单，避免笼统的"URL 非法"
        List<String> undefined = undefinedVars(url, vars);
        if (!undefined.isEmpty()) {
            item.setError("URL 含未定义变量: " + String.join(", ", undefined)
                    + (vars.isEmpty() ? "（当前无可用变量，请检查参数文件是否上传、变量名是否一致）"
                    : "（可用变量: " + String.join(", ", vars.keySet()) + "）"));
            return item;
        }
        item.setUrl(url);

        // 2) 请求头/请求体变量替换
        List<ScriptDebugVO.Kv> requestHeaders = new ArrayList<>();
        if (sampler.getHeaders() != null) {
            for (ScriptFormRequest.Header header : sampler.getHeaders()) {
                if (header != null && StringUtils.hasText(header.getK())) {
                    requestHeaders.add(new ScriptDebugVO.Kv(header.getK().trim(), resolveVars(trimToEmpty(header.getV()), vars)));
                }
            }
        }
        String body = resolveVars(trimToEmpty(sampler.getBody()), vars);
        // 请求体存在但未显式声明 Content-Type 时补 JSON（表单脚本请求体以 JSON 为主）
        if (StringUtils.hasText(body) && requestHeaders.stream().noneMatch(h -> h.getK().equalsIgnoreCase("content-type"))) {
            requestHeaders.add(new ScriptDebugVO.Kv("Content-Type", "application/json"));
        }
        item.setRequestHeaders(requestHeaders);
        item.setRequestBody(body);

        // 3) 发起请求
        HttpResponse<String> response;
        long elapsedMs;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT);
            requestHeaders.forEach(h -> builder.header(h.getK(), h.getV()));
            String method = item.getMethod();
            if (StringUtils.hasText(body) && BODY_METHODS.contains(method)) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else if (method.equals("GET")) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            long start = System.nanoTime();
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            item.setError("调试请求被中断");
            return item;
        } catch (IllegalArgumentException e) {
            item.setError("URL 非法或含未定义变量: " + url);
            return item;
        } catch (Exception e) {
            item.setError("请求失败: " + e.getMessage());
            return item;
        }

        String responseBody = response.body() == null ? "" : response.body();
        item.setStatusCode(response.statusCode());
        item.setElapsedMs(elapsedMs);
        item.setResponseBody(truncate(responseBody));
        item.setResponseHeaders(responseHeaders(response));
        item.setSuccess(response.statusCode() < 400);

        // 4) 断言：TEXT 响应体包含 / CODE 响应码相等
        List<ScriptDebugVO.AssertionResult> assertionResults = new ArrayList<>();
        boolean assertionsPassed = true;
        if (sampler.getAssertions() != null) {
            for (ScriptFormRequest.Assertion assertion : sampler.getAssertions()) {
                if (assertion == null || !StringUtils.hasText(assertion.getExpect())) {
                    continue;
                }
                String expect = resolveVars(assertion.getExpect().trim(), vars);
                boolean passed = "CODE".equals(assertion.getType())
                        ? expect.equals(String.valueOf(response.statusCode()))
                        : responseBody.contains(expect);
                assertionsPassed &= passed;
                ScriptDebugVO.AssertionResult result = new ScriptDebugVO.AssertionResult();
                result.setType(assertion.getType());
                result.setExpect(expect);
                result.setPassed(passed);
                assertionResults.add(result);
            }
        }
        item.setAssertions(assertionResults);
        item.setOk(item.isSuccess() && assertionsPassed);

        // 5) 提取器：按来源取文本 → 按类型提取 → 命中值回写变量池供后续接口使用
        List<ScriptDebugVO.ExtractorResult> extractorResults = new ArrayList<>();
        if (sampler.getExtractors() != null) {
            for (ScriptFormRequest.Extractor extractor : sampler.getExtractors()) {
                if (extractor == null || !StringUtils.hasText(extractor.getRefName())) {
                    continue;
                }
                String source = sourceText(extractor.getSource(), url, requestHeaders, response, responseBody);
                String value = extractByType(extractor, source);
                boolean hit = value != null;
                String finalValue = hit ? value : trimToEmpty(extractor.getDefaultValue());
                vars.put(extractor.getRefName().trim(), finalValue);
                ScriptDebugVO.ExtractorResult result = new ScriptDebugVO.ExtractorResult();
                result.setRefName(extractor.getRefName().trim());
                result.setHit(hit);
                result.setValue(finalValue);
                extractorResults.add(result);
            }
        }
        item.setExtractors(extractorResults);
        return item;
    }

    /**
     * 规范化请求方法：缺省 GET，统一大写
     *
     * @param method 定义中的方法名
     * @return 大写方法名
     */
    private String methodOf(String method) {
        return StringUtils.hasText(method) ? method.trim().toUpperCase(Locale.ROOT) : "GET";
    }

    /**
     * 相对路径拼接全局环境：protocol://host[:port]/path（与 FormScriptJmxBuilder.appendHttpSampler 一致）
     *
     * @param cfg      全局配置
     * @param relative 相对路径
     * @return 绝对 URL
     */
    private String buildAbsoluteUrl(ScriptFormRequest.Config cfg, String relative) {
        StringBuilder url = new StringBuilder();
        url.append(StringUtils.hasText(cfg.getProtocol()) ? cfg.getProtocol().trim() : "http");
        url.append("://").append(cfg.getHost().trim());
        if (cfg.getPort() != null) {
            url.append(':').append(cfg.getPort());
        }
        if (!relative.startsWith("/")) {
            url.append('/');
        }
        return url.append(relative).toString();
    }

    /**
     * JMeter 风格变量替换：${name} 命中变量池则替换，未定义变量保留原样（与 JMeter 运行时一致）
     *
     * @param text 原始文本
     * @param vars 变量池
     * @return 替换后文本
     */
    private String resolveVars(String text, Map<String, String> vars) {
        if (text == null || text.isEmpty() || vars.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1).trim());
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 响应头转键值对列表（同名头合并值，TreeMap 保证展示顺序稳定）
     *
     * @param response HTTP 响应
     * @return 头键值对列表
     */
    private List<ScriptDebugVO.Kv> responseHeaders(HttpResponse<String> response) {
        List<ScriptDebugVO.Kv> headers = new ArrayList<>();
        new TreeMap<>(response.headers().map()).forEach((k, values) ->
                headers.add(new ScriptDebugVO.Kv(k, String.join(", ", values))));
        return headers;
    }

    /**
     * 加载采样器引用的参数文件（CSV）首行数据到变量池：
     * - varNames 已指定时以其为列名；ignoreFirstLine=true 跳过文件首行（表头），数据从第二行取
     * - varNames 为空时文件首行即列名，数据从第二行取
     * - 文件缺失/读取失败仅记日志跳过，对应变量保持未定义（由 URL 校验给出明确提示）
     *
     * @param sampler 采样器定义（csvRefs 引用列表）
     * @param vars    变量池（写入 CSV 列名 → 首行值）
     */
    private void loadCsvVars(ScriptFormRequest.Sampler sampler, Map<String, String> vars) {
        if (sampler.getCsvRefs() == null) {
            return;
        }
        for (ScriptFormRequest.CsvRef ref : sampler.getCsvRefs()) {
            if (ref == null || ref.getFileId() == null) {
                continue;
            }
            try {
                DataFile file = dataFileMapper.selectById(ref.getFileId());
                if (file == null || !StringUtils.hasText(file.getStoragePath())) {
                    log.warn("[Debug] 参数文件不存在（fileId={}），相关变量未加载", ref.getFileId());
                    continue;
                }
                List<String> lines = readHeadLines(Paths.get(file.getStoragePath()), 2);
                if (lines.isEmpty()) {
                    continue;
                }
                String delimiter = StringUtils.hasText(ref.getDelimiter()) ? ref.getDelimiter() : ",";
                List<String> names;
                List<String> rows;
                if (StringUtils.hasText(ref.getVarNames())) {
                    // 显式指定列名：变量名列表固定按逗号拆分（与 JMeter CSVDataSet variableNames 语义一致，
                    // 与数据文件分隔符无关——数据为 | 分隔的 TXT 时 varNames 仍是逗号分隔的列名）
                    names = splitLine(ref.getVarNames(), ",");
                    rows = Boolean.TRUE.equals(ref.getIgnoreFirstLine()) && lines.size() > 1
                            ? lines.subList(1, lines.size()) : lines;
                } else {
                    // 未指定列名：文件首行即表头
                    names = splitLine(lines.get(0), delimiter);
                    rows = lines.size() > 1 ? lines.subList(1, lines.size()) : List.of();
                }
                if (rows.isEmpty() || names.isEmpty()) {
                    continue;
                }
                List<String> values = splitLine(rows.get(0), delimiter);
                for (int i = 0; i < names.size() && i < values.size(); i++) {
                    String name = names.get(i).trim();
                    if (!name.isEmpty()) {
                        vars.put(name, values.get(i));
                    }
                }
            } catch (Exception e) {
                log.warn("[Debug] 参数文件加载失败（fileId={}）: {}", ref.getFileId(), e.getMessage());
            }
        }
    }

    /**
     * 只读文件前 n 行（参数文件可能很大，调试只取表头与首条数据）
     *
     * @param path 文件路径
     * @param max  最多读取行数
     * @return 行列表（空文件/IO 异常返回空列表）
     */
    private List<String> readHeadLines(Path path, int max) {
        List<String> lines = new ArrayList<>();
        if (!Files.isReadable(path)) {
            return lines;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while (lines.size() < max && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            log.warn("[Debug] 参数文件读取失败（{}）: {}", path, e.getMessage());
        }
        return lines;
    }

    /**
     * 按分隔符拆行（保留空列，分隔符按字面量处理）
     *
     * @param line      原始行
     * @param delimiter 分隔符
     * @return 拆分后的列列表
     */
    private List<String> splitLine(String line, String delimiter) {
        return List.of(line.split(Pattern.quote(delimiter), -1));
    }

    /**
     * 找出文本中已替换后仍残留的未定义变量名（${xxx} 且不在变量池）
     *
     * @param text 已完成变量替换的文本
     * @param vars 变量池
     * @return 未定义变量名列表
     */
    private List<String> undefinedVars(String text, Map<String, String> vars) {
        List<String> names = new ArrayList<>();
        Matcher matcher = VAR_PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!vars.containsKey(name) && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 按提取来源取得目标文本
     *
     * @param source         来源标识
     * @param url            实际请求 URL
     * @param requestHeaders 请求头
     * @param response       HTTP 响应
     * @param responseBody   响应体
     * @return 目标文本（无对应来源时返回空串）
     */
    private String sourceText(String source, String url, List<ScriptDebugVO.Kv> requestHeaders,
                              HttpResponse<String> response, String responseBody) {
        if (source == null || "RESPONSE_BODY".equals(source)) {
            return responseBody;
        }
        switch (source) {
            case "RESPONSE_HEADERS":
                return response.headers().map().entrySet().stream()
                        .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                        .reduce((a, b) -> a + "\n" + b).orElse("");
            case "REQUEST_HEADERS":
                return requestHeaders.stream()
                        .map(h -> h.getK() + ": " + h.getV())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
            case "URL":
                return url;
            case "RESPONSE_CODE":
                return String.valueOf(response.statusCode());
            case "RESPONSE_MESSAGE":
                return "";
            default:
                return responseBody;
        }
    }

    /**
     * 按提取器类型执行提取：JSON 走 JSONPath 子集求值，REGEX 走正则+模板，BOUNDARY 走双边界
     *
     * @param extractor 提取器定义
     * @param source    目标文本
     * @return 提取值，未命中返回 null
     */
    private String extractByType(ScriptFormRequest.Extractor extractor, String source) {
        if (!StringUtils.hasText(extractor.getExpression())) {
            return null;
        }
        int matchNumber = extractor.getMatchNumber() == null ? 1 : extractor.getMatchNumber();
        try {
            switch (extractor.getType() == null ? "JSON" : extractor.getType()) {
                case "JSON":
                    return evalJsonPath(source, extractor.getExpression().trim(), matchNumber);
                case "REGEX":
                    return extractRegex(source, extractor, matchNumber);
                case "BOUNDARY":
                    return extractBoundary(source, extractor);
                default:
                    return null;
            }
        } catch (Exception e) {
            log.debug("[Debug] 提取器执行失败 refName={}: {}", extractor.getRefName(), e.getMessage());
            return null;
        }
    }

    /**
     * JSONPath 子集求值：支持 $.a.b、[0]、[*] 与递归下降 $..key；
     * matchNumber=1 取第一个命中，-1 全部命中逗号连接，0 视同 1（JMeter 随机语义取第一个）
     *
     * @param body        响应体文本
     * @param path        JSONPath 表达式
     * @param matchNumber 匹配序号
     * @return 提取值，未命中或 JSON 解析失败返回 null
     */
    private String evalJsonPath(String body, String path, int matchNumber) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
        String expr = path.startsWith("$") ? path.substring(1) : path;
        List<JsonNode> current = new ArrayList<>();
        current.add(root);
        int i = 0;
        while (i < expr.length() && !current.isEmpty()) {
            char c = expr.charAt(i);
            if (c == '.') {
                if (i + 1 < expr.length() && expr.charAt(i + 1) == '.') {
                    // 递归下降 ..key：收集全部后代同名字段
                    int j = i + 2;
                    StringBuilder name = new StringBuilder();
                    while (j < expr.length() && expr.charAt(j) != '.' && expr.charAt(j) != '[') {
                        name.append(expr.charAt(j));
                        j++;
                    }
                    List<JsonNode> next = new ArrayList<>();
                    for (JsonNode node : current) {
                        collectDescendants(node, name.toString(), next);
                    }
                    current = next;
                    i = j;
                } else {
                    // 普通字段 .name
                    int j = i + 1;
                    StringBuilder name = new StringBuilder();
                    while (j < expr.length() && expr.charAt(j) != '.' && expr.charAt(j) != '[') {
                        name.append(expr.charAt(j));
                        j++;
                    }
                    List<JsonNode> next = new ArrayList<>();
                    for (JsonNode node : current) {
                        JsonNode value = node.get(name.toString());
                        if (value != null) {
                            next.add(value);
                        }
                    }
                    current = next;
                    i = j;
                }
            } else if (c == '[') {
                // 数组下标 [0] 或通配 [*]
                int j = expr.indexOf(']', i);
                if (j < 0) {
                    return null;
                }
                String inner = expr.substring(i + 1, j);
                List<JsonNode> next = new ArrayList<>();
                if (inner.equals("*")) {
                    for (JsonNode node : current) {
                        if (node.isArray()) {
                            node.forEach(next::add);
                        }
                    }
                } else {
                    try {
                        int index = Integer.parseInt(inner.trim());
                        for (JsonNode node : current) {
                            if (node.isArray() && index >= 0 && index < node.size()) {
                                next.add(node.get(index));
                            }
                        }
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                current = next;
                i = j + 1;
            } else {
                return null;
            }
        }
        if (current.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : current) {
            values.add(node.isValueNode() ? node.asText() : node.toString());
        }
        return pickMatch(values, matchNumber);
    }

    /**
     * 递归收集节点树内全部同名字段的值（含根节点自身字段）
     *
     * @param node      起始节点
     * @param name      字段名
     * @param collector 结果收集列表
     */
    private void collectDescendants(JsonNode node, String name, List<JsonNode> collector) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode hit = node.get(name);
            if (hit != null) {
                collector.add(hit);
            }
        }
        if (node.isContainerNode()) {
            node.forEach(child -> collectDescendants(child, name, collector));
        }
    }

    /**
     * 正则提取：逐匹配应用模板（$1$ 取捕获分组），再按 matchNumber 选取
     *
     * @param source       目标文本
     * @param extractor    提取器定义
     * @param matchNumber  匹配序号
     * @return 提取值，未命中返回 null
     */
    private String extractRegex(String source, ScriptFormRequest.Extractor extractor, int matchNumber) {
        Pattern pattern = Pattern.compile(extractor.getExpression().trim());
        Matcher matcher = pattern.matcher(source);
        List<String> values = new ArrayList<>();
        while (matcher.find() && values.size() < 100) {
            values.add(applyTemplate(matcher, extractor.getTemplate()));
        }
        return values.isEmpty() ? null : pickMatch(values, matchNumber);
    }

    /**
     * 应用 JMeter 正则模板：将 $1$/$2$ 等占位替换为对应捕获分组（越界分组替换为空串）
     *
     * @param matcher   已命中的匹配器
     * @param template  模板（默认 $1$）
     * @return 模板展开后的值
     */
    private String applyTemplate(Matcher matcher, String template) {
        String text = StringUtils.hasText(template) ? template.trim() : "$1$";
        Matcher token = TEMPLATE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (token.find()) {
            int group = Integer.parseInt(token.group(1));
            String replacement = group >= 0 && group <= matcher.groupCount() ? matcher.group(group) : "";
            token.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        token.appendTail(result);
        return result.toString();
    }

    /**
     * 边界提取：左边界与右边界首次出现之间的内容
     *
     * @param source    目标文本
     * @param extractor 提取器定义
     * @return 提取值，任一边界未找到返回 null
     */
    private String extractBoundary(String source, ScriptFormRequest.Extractor extractor) {
        if (!StringUtils.hasText(extractor.getRightBoundary())) {
            return null;
        }
        int left = source.indexOf(extractor.getExpression().trim());
        if (left < 0) {
            return null;
        }
        int start = left + extractor.getExpression().trim().length();
        int right = source.indexOf(extractor.getRightBoundary().trim(), start);
        if (right < 0) {
            return null;
        }
        return source.substring(start, right);
    }

    /**
     * 按匹配序号选取提取值：1/0 取第一个（0 为 JMeter 随机语义，简化取第一个），
     * n>1 取第 n 个，-1 全部命中用逗号连接
     *
     * @param values       命中值列表
     * @param matchNumber  匹配序号
     * @return 选中的值
     */
    private String pickMatch(List<String> values, int matchNumber) {
        if (matchNumber < 0) {
            return String.join(",", values);
        }
        int index = matchNumber == 0 ? 0 : matchNumber - 1;
        return index < values.size() ? values.get(index) : null;
    }

    /**
     * 文本截断（超出 64KB 时追加截断提示）
     *
     * @param text 原始文本
     * @return 截断后文本
     */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_BODY_CHARS) + "\n…（已截断，原始长度 " + text.length() + " 字符）";
    }

    /**
     * null 安全 trim
     *
     * @param text 原始文本
     * @return 空串或 trim 后文本
     */
    private String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
