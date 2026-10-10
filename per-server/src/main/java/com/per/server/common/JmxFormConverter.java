package com.per.server.common;

import com.per.server.dto.ScriptFormRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JMX → 表单脚本转换器：解析 JMeter JMX XML，提取每个线程组下的 HTTP 接口信息
 * （URL/方法/请求头/请求体/断言/提取器/CSV 参数文件/思考时间/全局变量）转为平台 FormDef。
 * <p>
 * 转换原则（控制实现复杂度）：
 * - 只认 HTTPSamplerProxy 采样器；逻辑控制器（If/Loop/Transaction/Throughput 等）子树内的
 *   HTTP 接口"拍平"提取到所属组，控制器本身记入忽略提醒；
 * - 无法映射的插件（JSR223/BeanShell/JDBC 等非 HTTP 采样器、Cookie/Cache 等配置元件、
 *   随机定时器等）一律忽略并在 warnings 中提醒；
 * - CSVDataSet 的 filename 按 data_file 表名称匹配 fileId（由调用方传入映射），
 *   匹配不到的记入提醒（转换后引用丢失，可上传文件后重新关联）；
 * - 输出的 FormDef 保证平台合法：变量名非法字符剔除、分组超 10 截断、URL 无域名的采样器丢弃并提醒。
 */
@Component
public class JmxFormConverter {

    /** 平台表单支持的最大分组数（与 ScriptService.validateFormDef 一致），超出截断 */
    private static final int MAX_GROUPS = 10;

    /** 转换结果：表单定义 + 转换过程中被忽略的内容提醒 */
    public record ConvertResult(ScriptFormRequest.FormDef formDef, List<String> warnings) {
    }

    /**
     * 解析 JMX 文本并转换为平台表单定义
     *
     * @param jmxContent   JMX 脚本内容（XML 文本）
     * @param fileNameToId 文件库映射：CSVDataSet filename → data_file.id（空映射时全部 CSV 引用记提醒）
     * @return 表单定义与忽略项提醒
     * @throws BizException XML 非法或未找到任何可转换的 HTTP 接口
     */
    public ConvertResult convert(String jmxContent, Map<String, Long> fileNameToId) {
        Document doc = parseXml(jmxContent);
        List<String> warnings = new ArrayList<>();
        ScriptFormRequest.FormDef formDef = new ScriptFormRequest.FormDef();
        // 全局变量：TestPlan UDV + 线程组级 UDV 元件合并（线程组级覆盖同名）
        Map<String, String> variables = new LinkedHashMap<>();

        Element root = doc.getDocumentElement();
        // 标准结构：jmeterTestPlan > hashTree（根）> (TestPlan, hashTree（计划层）, ThreadGroup, hashTree, ...)
        Element rootTree = firstChildElement(root, "hashTree");
        Element testPlan = rootTree == null ? null : firstChildElement(rootTree, "TestPlan");
        if (testPlan != null) {
            collectUdv(testPlan, "TestPlan.user_defined_variables", variables);
        }
        // TestPlan 元素后紧跟的 hashTree 即线程组层；无 TestPlan 的异常结构退化用根 hashTree
        Element planTree = testPlan != null
                ? nextSiblingElement(testPlan, "hashTree") : rootTree;
        if (planTree == null) {
            throw new BizException("JMX 结构异常：未找到 TestPlan 下的 hashTree");
        }

        // 提取全局 HTTP 默认值（HTTP Request Defaults）作为环境补全
        ScriptFormRequest.Config config = new ScriptFormRequest.Config();
        config.setVariables(new ArrayList<>());
        for (Pair pair : childPairs(planTree)) {
            if ("ConfigTestElement".equals(pair.element().getAttribute("testclass"))
                    && pair.element().getAttribute("guiclass").contains("HttpDefaults")) {
                applyHttpDefaults(pair.element(), config);
            }
        }

        List<ScriptFormRequest.Group> groups = new ArrayList<>();
        List<Pair> topPairs = childPairs(planTree);
        int groupSeq = 0;
        for (Pair pair : topPairs) {
            Element el = pair.element();
            String testclass = el.getAttribute("testclass");
            // 线程组判定：testclass 以 ThreadGroup 结尾（含 Stepping/Ultimate/Concurrency/OpenModel 等衍生）
            if (testclass != null && testclass.endsWith("ThreadGroup")) {
                groupSeq += 1;
                if (groups.size() >= MAX_GROUPS) {
                    warnings.add("线程组「" + display(el) + "」超出平台分组上限 10，已截断忽略");
                    continue;
                }
                groups.add(convertThreadGroup(el, pair.hashTree(), groupSeq, formDef, fileNameToId, variables, warnings));
            } else if (isHttpSampler(testclass)) {
                // 极少数无线程组直挂 TestPlan 下的采样器：归入默认组（与无线程组兜底逻辑共用）
                ensureDefaultGroup(groups, warnings).getSamplers()
                        .add(parseSampler(el, pair.hashTree(), fileNameToId, warnings));
            } else if (!silentIgnore(testclass)) {
                warnings.add("忽略线程组外的元件 " + testclass + "「" + display(el) + "」");
            }
        }
        if (groups.stream().allMatch(g -> g.getSamplers() == null || g.getSamplers().isEmpty())) {
            throw new BizException("未在 JMX 中找到可转换的 HTTP 接口（仅支持 HTTPSamplerProxy 采样器）");
        }
        // 丢弃空组（线程组内无 HTTP 采样器）
        groups.removeIf(g -> g.getSamplers() == null || g.getSamplers().isEmpty());

        // 变量名合法性清洗：平台仅允许字母/数字/下划线，非法变量剔除并提醒
        variables.forEach((name, value) -> {
            if (name.matches("[A-Za-z0-9_]+")) {
                ScriptFormRequest.Variable v = new ScriptFormRequest.Variable();
                v.setName(name);
                v.setValue(value);
                config.getVariables().add(v);
            } else {
                warnings.add("忽略非法变量名「" + name + "」（平台仅支持字母/数字/下划线）");
            }
        });
        if (config.getVariables().isEmpty()) {
            config.setVariables(null);
        }
        if (!StringUtils.hasText(config.getHost())) {
            config.setHost(null);
        }
        formDef.setConfig(config);
        formDef.setGroups(groups);
        formDef.setThreadGroupName(groups.isEmpty() ? null : groups.get(0).getName());
        return new ConvertResult(formDef, warnings);
    }

    /**
     * 转换单个线程组为表单分组（SERIAL）：遍历组内元件，HTTP 采样器提取、控制器拍平、其余忽略提醒
     *
     * @param tgEl         线程组元素
     * @param tgTree       线程组对应 hashTree（可空）
     * @param groupSeq     线程组序号（命名兜底用）
     * @param formDef      表单定义（组级 ConstantTimer 写入 thinkTimeMs）
     * @param fileNameToId CSV 文件名 → fileId 映射
     * @param variables    全局变量收集容器（组级 UDV 写入）
     * @param warnings     忽略项提醒收集容器
     * @return 表单分组
     */
    private ScriptFormRequest.Group convertThreadGroup(Element tgEl, Element tgTree, int groupSeq,
                                                       ScriptFormRequest.FormDef formDef,
                                                       Map<String, Long> fileNameToId,
                                                       Map<String, String> variables, List<String> warnings) {
        ScriptFormRequest.Group group = new ScriptFormRequest.Group();
        group.setName(StringUtils.hasText(tgEl.getAttribute("testname")) ? tgEl.getAttribute("testname") : "线程组" + groupSeq);
        group.setExecution("SERIAL");
        List<ScriptFormRequest.Sampler> samplers = new ArrayList<>();
        List<ScriptFormRequest.CsvRef> pendingCsv = new ArrayList<>();
        if (tgTree != null) {
            walkGroupChildren(childPairs(tgTree), samplers, pendingCsv, group, formDef,
                    fileNameToId, variables, warnings, null);
        }
        // 组级 CSVDataSet 挂到组内首个采样器（平台模型 csvRefs 挂采样器）
        if (!pendingCsv.isEmpty()) {
            if (samplers.isEmpty()) {
                warnings.add("线程组「" + group.getName() + "」的参数文件引用被忽略（组内无 HTTP 接口）");
            } else {
                ScriptFormRequest.Sampler first = samplers.get(0);
                List<ScriptFormRequest.CsvRef> refs = first.getCsvRefs() == null
                        ? new ArrayList<>() : new ArrayList<>(first.getCsvRefs());
                refs.addAll(0, pendingCsv);
                first.setCsvRefs(refs);
            }
        }
        group.setSamplers(samplers);
        return group;
    }

    /**
     * 遍历线程组子层元件对（element + 其 hashTree）：HTTP 采样器解析、CSV 收集、
     * 思考时间提取、控制器递归拍平（trafficPercent 透传 ThroughputController 百分比）、其余忽略提醒
     *
     * @param pairs        元素对列表
     * @param samplers      采样器输出容器
     * @param pendingCsv    组级 CSV 引用输出容器
     * @param group         当前分组（thinkTime 写入）
     * @param fileNameToId  CSV 文件名 → fileId 映射
     * @param variables     变量收集容器（组级 UDV 写入）
     * @param warnings      忽略项提醒容器
     * @param inheritedPct  上层 ThroughputController 传入的流量百分比（可空）
     */
    private void walkGroupChildren(List<Pair> pairs, List<ScriptFormRequest.Sampler> samplers,
                                   List<ScriptFormRequest.CsvRef> pendingCsv, ScriptFormRequest.Group group,
                                   ScriptFormRequest.FormDef formDef,
                                   Map<String, Long> fileNameToId, Map<String, String> variables,
                                   List<String> warnings, Double inheritedPct) {
        for (Pair pair : pairs) {
            Element el = pair.element();
            String testclass = el.getAttribute("testclass");
            if (isHttpSampler(testclass)) {
                ScriptFormRequest.Sampler sampler = parseSampler(el, pair.hashTree(), fileNameToId, warnings);
                if (inheritedPct != null && sampler.getTrafficPercent() == null) {
                    sampler.setTrafficPercent(inheritedPct);
                }
                samplers.add(sampler);
            } else if ("CSVDataSet".equals(testclass)) {
                pendingCsv.add(parseCsvDataSet(el, fileNameToId, warnings));
            } else if ("ConstantTimer".equals(testclass)) {
                // 思考时间为表单全局字段：取首个非零定时器，后续定时器记提醒
                long delay = parseLong(childStringProp(el, "ConstantTimer.delay"), 0L);
                if (delay > 0 && (formDef.getThinkTimeMs() == null || formDef.getThinkTimeMs() == 0L)) {
                    formDef.setThinkTimeMs(delay);
                } else if (delay > 0) {
                    warnings.add("线程组「" + group.getName() + "」存在多个定时器，思考时间仅取 " + formDef.getThinkTimeMs() + "ms");
                }
            } else if ("Arguments".equals(testclass)) {
                // 组级 UDV 元件：变量并入全局（同名覆盖 TestPlan 定义）
                collectUdv(el, "Arguments.arguments", variables);
            } else if ("ConfigTestElement".equals(testclass)) {
                warnings.add("忽略配置元件 " + testclass + "「" + display(el) + "」（HTTP 默认值仅提取全局一份）");
            } else if (isController(testclass)) {
                // 逻辑控制器：子树内 HTTP 接口拍平提取，控制器本身记忽略提醒
                Double pct = "ThroughputController".equals(testclass) ? parseThroughputPercent(el) : null;
                warnings.add("逻辑控制器 " + testclass + "「" + display(el) + "」不转换，其下的 HTTP 接口已按顺序拍平"
                        + (pct != null ? "（放行比例 " + pct + "% 已应用到首个接口）" : ""));
                if (pair.hashTree() != null) {
                    walkGroupChildren(childPairs(pair.hashTree()), samplers, pendingCsv, group, formDef,
                            fileNameToId, variables, warnings, pct != null ? pct : inheritedPct);
                }
            } else if (!silentIgnore(testclass)) {
                warnings.add("忽略不支持的元件 " + testclass + "「" + display(el) + "」");
            }
        }
    }

    /**
     * 解析 HTTPSamplerProxy 为表单采样器：URL 拼装 + 请求体 + 子 hashTree 的
     * HeaderManager/ResponseAssertion/三种提取器/采样器级 CSVDataSet
     *
     * @param el          HTTPSamplerProxy 元素
     * @param childTree   采样器子 hashTree（可空）
     * @param fileNameToId CSV 文件名 → fileId 映射
     * @param warnings    忽略项提醒容器
     * @return 表单采样器
     */
    private ScriptFormRequest.Sampler parseSampler(Element el, Element childTree,
                                                   Map<String, Long> fileNameToId, List<String> warnings) {
        ScriptFormRequest.Sampler sampler = new ScriptFormRequest.Sampler();
        sampler.setName(StringUtils.hasText(el.getAttribute("testname")) ? el.getAttribute("testname") : "HTTP请求");
        String domain = childStringProp(el, "HTTPSampler.domain");
        String port = childStringProp(el, "HTTPSampler.port");
        String protocol = childStringProp(el, "HTTPSampler.protocol");
        String path = childStringProp(el, "HTTPSampler.path");
        String method = childStringProp(el, "HTTPSampler.method");
        sampler.setMethod(StringUtils.hasText(method) ? method.toUpperCase() : "GET");
        // URL 拼装：protocol://domain[:port]path（domain 为空时保留相对 path，由 ScriptService 按全局环境补全）
        StringBuilder url = new StringBuilder();
        if (StringUtils.hasText(domain)) {
            url.append(StringUtils.hasText(protocol) ? protocol : "http").append("://").append(domain.trim());
            if (StringUtils.hasText(port)) {
                url.append(':').append(port.trim());
            }
        }
        url.append(StringUtils.hasText(path) ? path.trim() : "/");
        sampler.setUrl(url.toString());
        parseBody(el, sampler, warnings);
        if (childTree != null) {
            for (Pair pair : childPairs(childTree)) {
                Element sub = pair.element();
                String testclass = sub.getAttribute("testclass");
                switch (testclass == null ? "" : testclass) {
                    case "HeaderManager" -> addHeaders(sub, sampler);
                    case "ResponseAssertion" -> addAssertion(sub, sampler, warnings);
                    case "RegexExtractor" -> addRegexExtractor(sub, sampler);
                    case "JSONPostProcessor" -> addJsonExtractor(sub, sampler);
                    case "BoundaryExtractor" -> addBoundaryExtractor(sub, sampler);
                    case "CSVDataSet" -> {
                        List<ScriptFormRequest.CsvRef> refs = sampler.getCsvRefs() == null
                                ? new ArrayList<>() : new ArrayList<>(sampler.getCsvRefs());
                        refs.add(parseCsvDataSet(sub, fileNameToId, warnings));
                        sampler.setCsvRefs(refs);
                    }
                    default -> {
                        if (!silentIgnore(testclass)) {
                            warnings.add("忽略接口「" + sampler.getName() + "」下的 " + testclass + " 元件");
                        }
                    }
                }
            }
        }
        return sampler;
    }

    /**
     * 解析请求体：postBodyRaw=true 取首个参数值；表单参数模式拼为 k=v&k2=v2 文本体并提醒
     *
     * @param el       HTTPSamplerProxy 元素
     * @param sampler  表单采样器（body 写入）
     * @param warnings 忽略项提醒容器
     */
    private void parseBody(Element el, ScriptFormRequest.Sampler sampler, List<String> warnings) {
        boolean raw = "true".equalsIgnoreCase(childBoolProp(el, "HTTPSampler.postBodyRaw"));
        Element arguments = firstElementProp(el, "HTTPsampler.Arguments");
        if (arguments == null) {
            return;
        }
        List<Element> args = argumentElements(arguments);
        if (args.isEmpty()) {
            return;
        }
        if (raw) {
            sampler.setBody(childStringProp(args.get(0), "Argument.value"));
            return;
        }
        // 非 raw 但仅一个无名参数：GUI "消息体" 模式的另一种导出形态，直接作为原始 body
        if (args.size() == 1 && !StringUtils.hasText(childStringProp(args.get(0), "Argument.name"))) {
            sampler.setBody(childStringProp(args.get(0), "Argument.value"));
            return;
        }
        StringBuilder form = new StringBuilder();
        for (Element arg : args) {
            String name = childStringProp(arg, "Argument.name");
            String value = childStringProp(arg, "Argument.value");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(name.trim()).append('=').append(value == null ? "" : value);
        }
        if (form.length() > 0) {
            sampler.setBody(form.toString());
            warnings.add("接口「" + sampler.getName() + "」的表单参数已拼接为文本请求体（k=v&…），请按需调整");
        }
    }

    /**
     * 解析采样器子级 HeaderManager 为请求头列表
     *
     * @param el      HeaderManager 元素
     * @param sampler 表单采样器（headers 写入）
     */
    private void addHeaders(Element el, ScriptFormRequest.Sampler sampler) {
        Element collection = firstCollectionProp(el, "HeaderManager.headers");
        if (collection == null) {
            return;
        }
        List<ScriptFormRequest.Header> headers = sampler.getHeaders() == null
                ? new ArrayList<>() : new ArrayList<>(sampler.getHeaders());
        for (Element prop : childElements(collection, "elementProp")) {
            ScriptFormRequest.Header header = new ScriptFormRequest.Header();
            header.setK(childStringProp(prop, "Header.name"));
            header.setV(childStringProp(prop, "Header.value"));
            if (StringUtils.hasText(header.getK())) {
                headers.add(header);
            }
        }
        sampler.setHeaders(headers.isEmpty() ? null : headers);
    }

    /**
     * 解析 ResponseAssertion 为断言：response_code 相等→CODE；response_data 包含→TEXT；
     * 其他断言字段（请求头/耗时等）平台不支持，记忽略提醒
     *
     * @param el       ResponseAssertion 元素
     * @param sampler  表单采样器（assertions 写入）
     * @param warnings 忽略项提醒容器
     */
    private void addAssertion(Element el, ScriptFormRequest.Sampler sampler, List<String> warnings) {
        String field = childStringProp(el, "Assertion.test_field");
        String expect = firstAssertionString(el);
        ScriptFormRequest.Assertion assertion = new ScriptFormRequest.Assertion();
        if ("Assertion.response_code".equals(field)) {
            assertion.setType("CODE");
        } else if ("Assertion.response_data".equals(field)) {
            assertion.setType("TEXT");
        } else {
            warnings.add("忽略接口「" + sampler.getName() + "」的断言（不支持的字段 " + field + "）");
            return;
        }
        assertion.setExpect(expect);
        List<ScriptFormRequest.Assertion> assertions = sampler.getAssertions() == null
                ? new ArrayList<>() : new ArrayList<>(sampler.getAssertions());
        assertions.add(assertion);
        sampler.setAssertions(assertions);
    }

    /**
     * 解析 RegexExtractor 为正则提取器（useHeaders 反映射为 source）
     *
     * @param el      RegexExtractor 元素
     * @param sampler 表单采样器（extractors 写入）
     */
    private void addRegexExtractor(Element el, ScriptFormRequest.Sampler sampler) {
        ScriptFormRequest.Extractor extractor = new ScriptFormRequest.Extractor();
        extractor.setType("REGEX");
        extractor.setRefName(childStringProp(el, "RegexExtractor.refname"));
        extractor.setExpression(childStringProp(el, "RegexExtractor.regex"));
        extractor.setTemplate(childStringProp(el, "RegexExtractor.template"));
        extractor.setMatchNumber(parseInteger(childStringProp(el, "RegexExtractor.match_number"), 1));
        extractor.setDefaultValue(childStringProp(el, "RegexExtractor.default"));
        extractor.setSource(useHeadersToSource(childStringProp(el, "RegexExtractor.useHeaders")));
        addExtractor(sampler, extractor);
    }

    /**
     * 解析 JSONPostProcessor 为 JSON 提取器（SOURCE 取值映射为平台 source）
     *
     * @param el      JSONPostProcessor 元素
     * @param sampler 表单采样器（extractors 写入）
     */
    private void addJsonExtractor(Element el, ScriptFormRequest.Sampler sampler) {
        ScriptFormRequest.Extractor extractor = new ScriptFormRequest.Extractor();
        extractor.setType("JSON");
        extractor.setRefName(childStringProp(el, "JSONPostProcessor.referenceNames"));
        extractor.setExpression(childStringProp(el, "JSONPostProcessor.jsonPathExprs"));
        extractor.setMatchNumber(parseInteger(childStringProp(el, "JSONPostProcessor.match_numbers"), 1));
        extractor.setDefaultValue(childStringProp(el, "JSONPostProcessor.defaultValues"));
        String source = childStringProp(el, "JSONPostProcessor.SOURCE");
        extractor.setSource(StringUtils.hasText(source) ? source.toUpperCase() : "RESPONSE_BODY");
        addExtractor(sampler, extractor);
    }

    /**
     * 解析 BoundaryExtractor 为边界提取器
     *
     * @param el      BoundaryExtractor 元素
     * @param sampler 表单采样器（extractors 写入）
     */
    private void addBoundaryExtractor(Element el, ScriptFormRequest.Sampler sampler) {
        ScriptFormRequest.Extractor extractor = new ScriptFormRequest.Extractor();
        extractor.setType("BOUNDARY");
        extractor.setRefName(childStringProp(el, "BoundaryExtractor.refname"));
        extractor.setExpression(childStringProp(el, "BoundaryExtractor.lboundary"));
        extractor.setRightBoundary(childStringProp(el, "BoundaryExtractor.rboundary"));
        extractor.setMatchNumber(parseInteger(childStringProp(el, "BoundaryExtractor.match_number"), 1));
        extractor.setDefaultValue(childStringProp(el, "BoundaryExtractor.default"));
        extractor.setSource(useHeadersToSource(childStringProp(el, "BoundaryExtractor.useHeaders")));
        addExtractor(sampler, extractor);
    }

    /**
     * 提取器加入采样器列表（refName 为空时丢弃，无法被后续引用）
     *
     * @param sampler   表单采样器
     * @param extractor 提取器定义
     */
    private void addExtractor(ScriptFormRequest.Sampler sampler, ScriptFormRequest.Extractor extractor) {
        if (!StringUtils.hasText(extractor.getRefName())) {
            return;
        }
        List<ScriptFormRequest.Extractor> extractors = sampler.getExtractors() == null
                ? new ArrayList<>() : new ArrayList<>(sampler.getExtractors());
        extractors.add(extractor);
        sampler.setExtractors(extractors);
    }

    /**
     * 解析 CSVDataSet 为表单 CSV 引用：filename 匹配文件库拿 fileId，
     * 匹配不到记提醒（转换后引用丢失，可上传文件后重新关联）
     *
     * @param el          CSVDataSet 元素
     * @param fileNameToId CSV 文件名 → fileId 映射
     * @param warnings    忽略项提醒容器
     * @return CSV 引用定义
     */
    private ScriptFormRequest.CsvRef parseCsvDataSet(Element el, Map<String, Long> fileNameToId, List<String> warnings) {
        ScriptFormRequest.CsvRef csvRef = new ScriptFormRequest.CsvRef();
        String filename = childStringProp(el, "filename");
        csvRef.setVarNames(childStringProp(el, "variableNames"));
        String delimiter = childStringProp(el, "delimiter");
        csvRef.setDelimiter(StringUtils.hasText(delimiter) ? delimiter : ",");
        csvRef.setIgnoreFirstLine("true".equalsIgnoreCase(childBoolProp(el, "ignoreFirstLine")));
        csvRef.setRecycle(!"false".equalsIgnoreCase(childBoolProp(el, "recycle")));
        String shareMode = childStringProp(el, "shareMode");
        csvRef.setShareMode(shareMode);
        Long fileId = fileNameToId.get(filename == null ? "" : filename.trim());
        if (fileId != null) {
            csvRef.setFileId(fileId);
        } else {
            warnings.add("参数文件「" + filename + "」未在文件库中找到，转换后引用丢失，请先上传再在脚本中重新关联");
        }
        return csvRef;
    }

    /**
     * 判定是否 HTTP 采样器（平台仅支持 HTTPSamplerProxy）
     *
     * @param testclass 元件 testclass 属性
     * @return true 表示 HTTP 采样器
     */
    private boolean isHttpSampler(String testclass) {
        return "HTTPSamplerProxy".equals(testclass);
    }

    /**
     * 判定是否逻辑控制器（testclass 以 Controller 结尾）
     *
     * @param testclass 元件 testclass 属性
     * @return true 表示逻辑控制器
     */
    private boolean isController(String testclass) {
        return testclass != null && testclass.endsWith("Controller");
    }

    /**
     * 判定是否静默忽略的元件（极常见且与压测逻辑无关，不产生提醒噪音）
     *
     * @param testclass 元件 testclass 属性
     * @return true 表示静默忽略
     */
    private boolean silentIgnore(String testclass) {
        if (testclass == null) {
            return true;
        }
        return switch (testclass) {
            // 结果收集/监听器（每个 JMX 必带，不影响脚本行为）
            case "ResultCollector" -> true;
            default -> false;
        };
    }

    /**
     * 解析 ThroughputController 百分比模式的放行比例（style=1 时 percentThroughput 生效）；
     * style 可能导出为 stringProp 或 intProp 两种形态
     *
     * @param el ThroughputController 元素
     * @return 放行百分比；非百分比模式返回 null
     */
    private Double parseThroughputPercent(Element el) {
        String style = childAnyProp(el, "ThroughputController.style");
        if (!"1".equals(StringUtils.hasText(style) ? style.trim() : "")) {
            return null;
        }
        Double percent = parseDouble(childStringProp(el, "ThroughputController.percentThroughput"), null);
        return percent != null && percent > 0 && percent < 100 ? percent : null;
    }

    /**
     * 收集 UDV 变量：elementProp（Arguments 容器）下 collectionProp 内的 Argument 条目
     *
     * @param container    Arguments 容器元素（TestPlan 或独立 UDV 元件）
     * @param propName     子元素属性名（TestPlan 用 elementProp name / 组级用 collectionProp name）
     * @param variables    变量输出容器（同名覆盖）
     */
    private void collectUdv(Element container, String propName, Map<String, String> variables) {
        Element arguments = firstElementProp(container, propName);
        if (arguments == null) {
            arguments = firstCollectionProp(container, propName) != null ? container : null;
        }
        if (arguments == null) {
            return;
        }
        Element collection = firstCollectionProp(arguments, "Arguments.arguments");
        if (collection == null) {
            collection = firstCollectionProp(container, propName);
        }
        if (collection == null) {
            return;
        }
        for (Element prop : childElements(collection, "elementProp")) {
            String name = childStringProp(prop, "Argument.name");
            if (StringUtils.hasText(name)) {
                variables.put(name.trim(), childStringProp(prop, "Argument.value"));
            }
        }
    }

    /**
     * 应用 HTTP Request Defaults（全局默认值）到表单全局配置：仅提取 protocol/domain/port
     *
     * @param el     ConfigTestElement（HttpDefaults）元素
     * @param config 全局配置
     */
    private void applyHttpDefaults(Element el, ScriptFormRequest.Config config) {
        String domain = childStringProp(el, "HTTPSampler.domain");
        if (StringUtils.hasText(domain)) {
            config.setProtocol(StringUtils.hasText(childStringProp(el, "HTTPSampler.protocol")) ? "https" : "http");
            config.setHost(domain.trim());
            String port = childStringProp(el, "HTTPSampler.port");
            if (StringUtils.hasText(port)) {
                config.setPort(parseInteger(port, null));
            }
            String protocol = childStringProp(el, "HTTPSampler.protocol");
            if (StringUtils.hasText(protocol)) {
                config.setProtocol(protocol.trim());
            }
        }
    }

    /**
     * 获取/创建默认分组（无线程组直挂采样器的兜底）
     *
     * @param groups   分组列表
     * @param warnings 忽略项提醒容器
     * @return 默认分组
     */
    private ScriptFormRequest.Group ensureDefaultGroup(List<ScriptFormRequest.Group> groups, List<String> warnings) {
        if (!groups.isEmpty()) {
            return groups.get(groups.size() - 1);
        }
        warnings.add("JMX 中未找到线程组，接口已归入默认分组");
        ScriptFormRequest.Group group = new ScriptFormRequest.Group();
        group.setName("线程组");
        group.setExecution("SERIAL");
        group.setSamplers(new ArrayList<>());
        groups.add(group);
        return group;
    }

    // ==================== XML 工具方法 ====================

    /** 元素与其对应 hashTree 的配对（JMX 结构：element 节点后紧跟其子 hashTree） */
    private record Pair(Element element, Element hashTree) {
    }

    /**
     * 解析 JMX XML 文本为 Document（禁 DTD/外部实体防 XXE）
     *
     * @param xml XML 文本
     * @return DOM Document
     * @throws BizException XML 非法
     */
    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException("JMX 文件不是合法的 XML：" + e.getMessage());
        }
    }

    /**
     * 收集容器下"element + 其兄弟 hashTree"配对列表
     *
     * @param parent 容器元素
     * @return 配对列表（hashTree 可能为 null）
     */
    private List<Pair> childPairs(Element parent) {
        List<Pair> pairs = new ArrayList<>();
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el) {
                children.add(el);
            }
        }
        for (int i = 0; i < children.size(); i++) {
            Element el = children.get(i);
            Element tree = null;
            if (i + 1 < children.size() && "hashTree".equals(children.get(i + 1).getTagName())) {
                tree = children.get(i + 1);
                i += 1;
            }
            pairs.add(new Pair(el, tree));
        }
        return pairs;
    }

    /**
     * 查找首个指定标签名的直接子元素
     *
     * @param parent 父元素
     * @param tag    标签名
     * @return 子元素或 null
     */
    private Element firstChildElement(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el && tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    /**
     * 查找指定标签名的下一个兄弟元素
     *
     * @param current 当前元素
     * @param tag     标签名
     * @return 兄弟元素或 null
     */
    private Element nextSiblingElement(Element current, String tag) {
        if (current == null) {
            return null;
        }
        for (Node node = current.getNextSibling(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el && tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    /**
     * 查找首个指定 name 的 elementProp 直接子元素
     *
     * @param parent   父元素
     * @param propName elementProp 的 name 属性
     * @return elementProp 或 null
     */
    private Element firstElementProp(Element parent, String propName) {
        for (Element el : childElements(parent, "elementProp")) {
            if (propName.equals(el.getAttribute("name"))) {
                return el;
            }
        }
        return null;
    }

    /**
     * 查找首个指定 name 的 collectionProp 直接子元素
     *
     * @param parent   父元素
     * @param propName collectionProp 的 name 属性
     * @return collectionProp 或 null
     */
    private Element firstCollectionProp(Element parent, String propName) {
        for (Element el : childElements(parent, "collectionProp")) {
            if (propName.equals(el.getAttribute("name"))) {
                return el;
            }
        }
        return null;
    }

    /**
     * 收集指定标签名的全部直接子元素
     *
     * @param parent 父元素
     * @param tag    标签名
     * @return 子元素列表
     */
    private List<Element> childElements(Element parent, String tag) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element el && tag.equals(el.getTagName())) {
                elements.add(el);
            }
        }
        return elements;
    }

    /**
     * 读取首个指定 name 的 stringProp 直接子元素的文本值
     *
     * @param parent   父元素
     * @param propName stringProp 的 name 属性
     * @return 文本值（不存在返回 null）
     */
    private String childStringProp(Element parent, String propName) {
        for (Element el : childElements(parent, "stringProp")) {
            if (propName.equals(el.getAttribute("name"))) {
                return el.getTextContent();
            }
        }
        return null;
    }

    /**
     * 读取首个指定 name 的 boolProp 直接子元素的文本值
     *
     * @param parent   父元素
     * @param propName boolProp 的 name 属性
     * @return 文本值（不存在返回 null）
     */
    private String childBoolProp(Element parent, String propName) {
        for (Element el : childElements(parent, "boolProp")) {
            if (propName.equals(el.getAttribute("name"))) {
                return el.getTextContent();
            }
        }
        return null;
    }

    /**
     * 读取首个指定 name 的属性值（stringProp / intProp / longProp 任意形态，
     * 兼容不同版本 JMeter 导出差异，如 ThroughputController.style）
     *
     * @param parent   父元素
     * @param propName 属性 name
     * @return 文本值（不存在返回 null）
     */
    private String childAnyProp(Element parent, String propName) {
        String value = childStringProp(parent, propName);
        if (value != null) {
            return value;
        }
        for (String tag : List.of("intProp", "longProp")) {
            for (Element el : childElements(parent, tag)) {
                if (propName.equals(el.getAttribute("name"))) {
                    return el.getTextContent();
                }
            }
        }
        return null;
    }

    /**
     * 读取断言的期望字符串（Asserion.test_strings 集合内首个 stringProp）
     *
     * @param assertionEl ResponseAssertion 元素
     * @return 期望值（无则空串）
     */
    private String firstAssertionString(Element assertionEl) {
        Element collection = firstCollectionProp(assertionEl, "Asserion.test_strings");
        if (collection == null) {
            return "";
        }
        for (Element el : childElements(collection, "stringProp")) {
            return el.getTextContent();
        }
        return "";
    }

    /**
     * 提取 HTTPsampler.Arguments 下全部 HTTPArgument 条目
     *
     * @param arguments Arguments elementProp 容器
     * @return 参数条目列表
     */
    private List<Element> argumentElements(Element arguments) {
        Element collection = firstCollectionProp(arguments, "Arguments.arguments");
        if (collection == null) {
            return List.of();
        }
        return childElements(collection, "elementProp");
    }

    /**
     * useHeaders 属性反向映射为平台提取来源
     *
     * @param useHeaders RegexExtractor/BoundaryExtractor 的 useHeaders 取值
     * @return 平台 source 值
     */
    private String useHeadersToSource(String useHeaders) {
        if (!StringUtils.hasText(useHeaders)) {
            return "RESPONSE_BODY";
        }
        return switch (useHeaders.trim()) {
            case "true" -> "RESPONSE_HEADERS";
            case "request headers" -> "REQUEST_HEADERS";
            case "url" -> "URL";
            case "code" -> "RESPONSE_CODE";
            case "message" -> "RESPONSE_MESSAGE";
            default -> "RESPONSE_BODY";
        };
    }

    /**
     * 元素展示名（testname 属性，空则用 testclass）
     *
     * @param el 元素
     * @return 展示名
     */
    private String display(Element el) {
        String name = el.getAttribute("testname");
        return StringUtils.hasText(name) ? name : el.getAttribute("testclass");
    }

    /**
     * 解析长整型（空/非法返回默认值）
     *
     * @param value        文本值
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private long parseLong(String value, long defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析整型（空/非法返回默认值）
     *
     * @param value        文本值
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private Integer parseInteger(String value, Integer defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析浮点型（空/非法返回默认值）
     *
     * @param value        文本值
     * @param defaultValue 默认值
     * @return 解析结果
     */
    private Double parseDouble(String value, Double defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
