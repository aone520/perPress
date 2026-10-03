package com.per.server.service;

import com.per.server.common.BizException;
import com.per.server.common.TpsCapacity;
import com.per.server.common.WeightSplitter;
import com.per.server.dto.ScriptFormRequest;
import com.per.server.dto.TaskCreateRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表单脚本 JMX 渲染器：将表单场景定义渲染为可被 JMeter 5.6 直接运行的合法 JMX。
 * ThreadGroup 属性化（threads/rampup/duration 由 __P 函数读取，支持 -J 参数覆盖），
 * 结构：ThreadGroup → CSVDataSet/HTTPSamplerProxy/HeaderManager/ResponseAssertion/ConstantTimer。
 * M3 扩展三种压测模式：CONCURRENT（单线程组）/ FIXED_TPS（吞吐量定时器）/ STEPPED（堆叠线程组阶梯）
 */
@Component
public class FormScriptJmxBuilder {

    /** URL 解析失败时的兜底提示 */
    private static final String BAD_URL = "采样器 url 非法：";

    /**
     * 渲染表单场景定义为 JMX 文本（CONCURRENT 默认形态）
     *
     * @param def        表单场景定义
     * @param fileNames  CSV 引用的文件ID → 原始文件名映射（CSVDataSet filename 用原名）
     * @return 标准 JMX 文本（jmeterTestPlan version=1.2 properties=5.0）
     */
    public String build(ScriptFormRequest.FormDef def, Map<Long, String> fileNames) {
        return buildWithMode(def, fileNames, "CONCURRENT", null);
    }

    /**
     * 按压测模式渲染 JMX：先归一化编排分组——单串行组走兼容分支（输出与旧版逐字节一致），
     * 多单元（并行接口/多组）走 buildMultiUnit（每执行单元一个 ThreadGroup，压力按权重拆分）。
     * 全部属性化 __P：任务下发时按节点 -J 覆盖
     *
     * @param def       表单场景定义
     * @param fileNames CSV 引用的文件ID → 原始文件名映射
     * @param mode      压测模式（CONCURRENT/FIXED_TPS/STEPPED）
     * @param config    模式参数（JMX 内嵌 __P 默认值，任务下发时按节点 -J 覆盖；weights 为占比）
     * @return 标准 JMX 文本
     */
    public String buildWithMode(ScriptFormRequest.FormDef def, Map<Long, String> fileNames,
                                String mode, TaskCreateRequest.Config config) {
        String targetMode = StringUtils.hasText(mode) ? mode : "CONCURRENT";
        List<ScriptFormRequest.Group> groups = normalizeGroups(def);
        // 兼容分支：单 SERIAL 组（含全部旧结构脚本），渲染结构与改造前完全一致
        boolean legacy = groups.size() == 1 && !"PARALLEL".equalsIgnoreCase(groups.get(0).getExecution());
        if (!legacy) {
            return buildMultiUnit(def, groups, fileNames, targetMode, config);
        }
        ScriptFormRequest.FormDef legacyDef = def;
        List<ScriptFormRequest.Sampler> samplers = groups.get(0).getSamplers();
        StringBuilder jmx = new StringBuilder();
        jmx.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        jmx.append("<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n");
        jmx.append("  <hashTree>\n");
        // 标准结构：根 hashTree → TestPlan → 其子 hashTree → ThreadGroup → 线程组子 hashTree（缺 TestPlan 会报 Could not find the TestPlan class）
        appendTestPlan(jmx, def);
        jmx.append("    <hashTree>\n");
        switch (targetMode) {
            case "FIXED_TPS" -> {
                int duration = config != null && config.getDurationSeconds() != null ? config.getDurationSeconds() : 300;
                int tps = config != null && config.getTps() != null ? config.getTps() : 100;
                int threads = config != null && config.getMaxThreads() != null
                        ? config.getMaxThreads() : TpsCapacity.defaultThreads(tps,
                        config == null ? null : config.getExpectedResponseMs());
                // 启动爬坡时长可配（默认 10s）
                int rampup = config != null && config.getRampupSeconds() != null ? config.getRampupSeconds() : 10;
                appendPropertyThreadGroup(jmx, legacyDef, "tg0", threads, rampup, 0, duration);
                jmx.append("      <hashTree>\n");
                // 定时器仅挂首个采样器：每次业务迭代只限速一次，目标 TPS 表示完整串行业务链路 TPS
                appendSamplers(jmx, samplers, legacyDef.getThinkTimeMs(), fileNames, def, targetMode,
                        new TimerSpec("tg0", (long) tps * 60 / (double) threads));
                jmx.append("      </hashTree>\n");
            }
            case "STEPPED" -> appendSteppedGroups(jmx, legacyDef, samplers, fileNames, config);
            default -> {
                int threads = config != null && config.getThreads() != null ? config.getThreads() : 100;
                int rampup = config != null && config.getRampupSeconds() != null ? config.getRampupSeconds() : 60;
                int duration = config != null && config.getDurationSeconds() != null ? config.getDurationSeconds() : 300;
                appendConcurrentThreadGroup(jmx, legacyDef, threads, rampup, duration);
                jmx.append("      <hashTree>\n");
                appendSamplers(jmx, samplers, legacyDef.getThinkTimeMs(), fileNames, def, targetMode);
                jmx.append("      </hashTree>\n");
            }
        }
        jmx.append("    </hashTree>\n");
        jmx.append("  </hashTree>\n");
        jmx.append("</jmeterTestPlan>\n");
        return jmx.toString();
    }

    /**
     * 多单元渲染：执行单元（串行组整体 / 并行组内单个接口）各自渲染独立 ThreadGroup（TG 间天然并行），
     * 压力总量按 weights 拆分到单元（余数补给占比大的单元），JMX 内嵌拆分后的绝对默认值：
     * - CONCURRENT：每单元 TG，tg{u}.threads = threads×权重
     * - FIXED_TPS：每单元 TG + 首采样器 ConstantThroughputTimer，目标吞吐按业务迭代 TPS×权重拆分
     * - STEPPED：每段×每单元 TG（prefix=tg{seg}x{u}），段值×权重后按单位渲染
     *
     * @param def       表单场景定义
     * @param groups    归一化后的编排分组
     * @param fileNames CSV 引用的文件ID → 原始文件名映射
     * @param mode      压测模式
     * @param config    模式参数（含 weights，可空则均分）
     * @return 标准 JMX 文本
     */
    private String buildMultiUnit(ScriptFormRequest.FormDef def, List<ScriptFormRequest.Group> groups,
                                  Map<Long, String> fileNames, String mode, TaskCreateRequest.Config config) {
        List<ExecUnit> units = expandUnits(groups);
        int unitCount = units.size();
        int[] weights = WeightSplitter.ensureWeights(config == null ? null : config.getWeights(), unitCount);
        StringBuilder jmx = new StringBuilder();
        jmx.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        jmx.append("<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n");
        jmx.append("  <hashTree>\n");
        appendTestPlan(jmx, def);
        jmx.append("    <hashTree>\n");
        switch (mode) {
            case "FIXED_TPS" -> {
                int duration = config != null && config.getDurationSeconds() != null ? config.getDurationSeconds() : 300;
                int tps = config != null && config.getTps() != null ? config.getTps() : 100;
                // 启动爬坡时长可配（默认 10s：线程爬坡即吞吐爬坡，60s 硬编码太慢）
                int rampup = config != null && config.getRampupSeconds() != null ? config.getRampupSeconds() : 10;
                long[] unitTpsPerMin = WeightSplitter.splitLong((long) tps * 60, weights);
                for (int u = 0; u < unitCount; u++) {
                    String prefix = "tg" + u;
                    // 单元目标 TPS（向上取整）→ 线程数 = 单元TPS×2（上限 2000），保证打满该单元吞吐
                    int unitTps = (int) ((unitTpsPerMin[u] + 59) / 60);
                    int threads = TpsCapacity.defaultThreads(unitTps, config.getExpectedResponseMs());
                    appendNamedPropertyThreadGroup(jmx, unitDisplayName(units.get(u), prefix), prefix,
                            threads, rampup, 0, duration);
                    jmx.append("      <hashTree>\n");
                    appendUnitSamplers(jmx, units.get(u), fileNames, def.getThinkTimeMs(), def, mode,
                            new TimerSpec(prefix, unitTpsPerMin[u] / (double) threads));
                    jmx.append("      </hashTree>\n");
                }
            }
            case "STEPPED" -> {
                List<SteppedPlan.Segment> segments = SteppedPlan.expand(config.getStart(), config.getStep(),
                        config.getStepSeconds(), config.getPeak(), config.getPeakSeconds());
                int rampup = config.getRampupSeconds() == null ? 30 : config.getRampupSeconds();
                boolean tpsUnit = "TPS".equalsIgnoreCase(config.getUnit());
                for (int s = 0; s < segments.size(); s++) {
                    SteppedPlan.Segment segment = segments.get(s);
                    // 每段负载值先按权重拆分到单元，再按单位渲染（TPS 单位线程=值×2 上限 2000）
                    int[] unitValues = WeightSplitter.splitInt(segment.getValue(), weights);
                    for (int u = 0; u < unitCount; u++) {
                        String prefix = "tg" + s + "x" + u;
                        int value = Math.max(1, unitValues[u]);
                        int threads = tpsUnit
                                ? TpsCapacity.defaultThreads(value, config.getExpectedResponseMs())
                                : value;
                        appendNamedPropertyThreadGroup(jmx, unitDisplayName(units.get(u), prefix), prefix,
                                threads, rampup, segment.getDelaySeconds(), segment.getDurationSeconds());
                        jmx.append("      <hashTree>\n");
                        TimerSpec timer = tpsUnit
                                ? new TimerSpec(prefix, (long) value * 60 / (double) threads) : null;
                        appendUnitSamplers(jmx, units.get(u), fileNames, def.getThinkTimeMs(), def, mode, timer);
                        jmx.append("      </hashTree>\n");
                    }
                }
            }
            default -> {
                int threads = config != null && config.getThreads() != null ? config.getThreads() : 100;
                int rampup = config != null && config.getRampupSeconds() != null ? config.getRampupSeconds() : 60;
                int duration = config != null && config.getDurationSeconds() != null ? config.getDurationSeconds() : 300;
                int[] unitThreads = WeightSplitter.splitInt(threads, weights);
                for (int u = 0; u < unitCount; u++) {
                    String prefix = "tg" + u;
                    appendNamedPropertyThreadGroup(jmx, unitDisplayName(units.get(u), prefix), prefix,
                            Math.max(1, unitThreads[u]), rampup, 0, duration);
                    jmx.append("      <hashTree>\n");
                    appendUnitSamplers(jmx, units.get(u), fileNames, def.getThinkTimeMs(), def, mode);
                    jmx.append("      </hashTree>\n");
                }
            }
        }
        jmx.append("    </hashTree>\n");
        jmx.append("  </hashTree>\n");
        jmx.append("</jmeterTestPlan>\n");
        return jmx.toString();
    }

    /**
     * 归一化编排分组：无 groups 时把顶层 samplers 包成单个 SERIAL 组（旧数据兼容）；
     * groups 与 samplers 全空时抛业务异常（兼容旧提示文案）
     *
     * @param def 表单场景定义
     * @return 归一化后的分组列表（非空）
     */
    public static List<ScriptFormRequest.Group> normalizeGroups(ScriptFormRequest.FormDef def) {
        if (def.getGroups() != null && !def.getGroups().isEmpty()) {
            for (ScriptFormRequest.Group group : def.getGroups()) {
                if (group.getSamplers() == null || group.getSamplers().isEmpty()) {
                    throw new BizException("组内samplers不能为空");
                }
            }
            return def.getGroups();
        }
        if (def.getSamplers() == null || def.getSamplers().isEmpty()) {
            throw new BizException("samplers不能为空");
        }
        ScriptFormRequest.Group single = new ScriptFormRequest.Group();
        single.setName(def.getThreadGroupName());
        single.setExecution("SERIAL");
        single.setSamplers(def.getSamplers());
        return List.of(single);
    }

    /**
     * 执行单元：流量占比的最小分配粒度——串行组整体为 1 个单元，并行组内每个接口各 1 个单元
     */
    public record ExecUnit(String name, List<ScriptFormRequest.Sampler> samplers) {
    }

    /**
     * 展开执行单元：SERIAL 组 → 1 个单元（组内全部采样器）；PARALLEL 组 → 每接口 1 个单元（单采样器）
     *
     * @param groups 归一化后的编排分组
     * @return 执行单元列表（非空）
     */
    public static List<ExecUnit> expandUnits(List<ScriptFormRequest.Group> groups) {
        List<ExecUnit> units = new ArrayList<>();
        for (ScriptFormRequest.Group group : groups) {
            String groupLabel = StringUtils.hasText(group.getName()) ? group.getName().trim() : "接口组";
            if ("PARALLEL".equalsIgnoreCase(group.getExecution())) {
                for (ScriptFormRequest.Sampler sampler : group.getSamplers()) {
                    String samplerName = StringUtils.hasText(sampler.getName())
                            ? sampler.getName().trim() : sampler.getUrl();
                    units.add(new ExecUnit(groupLabel + "-" + samplerName, List.of(sampler)));
                }
            } else {
                units.add(new ExecUnit(groupLabel, group.getSamplers()));
            }
        }
        return units;
    }

    /**
     * 线程组显示名：单元名 + 属性前缀（与兼容分支 tgName-prefix 风格一致）
     *
     * @param unit   执行单元
     * @param prefix 属性前缀（tg0/tg1x0...）
     * @return 线程组 testname
     */
    private static String unitDisplayName(ExecUnit unit, String prefix) {
        return unit.name() + "-" + prefix;
    }

    /**
     * 追加单个执行单元的采样器与思考时间（SERIAL 单元挂组内全部，PARALLEL 单元挂单个）
     *
     * @param jmx         JMX 输出缓冲
     * @param unit        执行单元
     * @param fileNames   CSV 引用的文件ID → 原始文件名映射
     * @param thinkTimeMs 思考时间（毫秒）
     * @param def         表单场景定义（取全局配置用于相对路径补全）
     * @param mode        压测模式（FIXED_TPS 时串行链路内渲染接口级吞吐量控制器漏斗）
     */
    private void appendUnitSamplers(StringBuilder jmx, ExecUnit unit,
                                    Map<Long, String> fileNames, Long thinkTimeMs,
                                    ScriptFormRequest.FormDef def, String mode) {
        appendUnitSamplers(jmx, unit, fileNames, thinkTimeMs, def, mode, null);
    }

    private void appendUnitSamplers(StringBuilder jmx, ExecUnit unit,
                                    Map<Long, String> fileNames, Long thinkTimeMs,
                                    ScriptFormRequest.FormDef def, String mode, TimerSpec timer) {
        for (int i = 0; i < unit.samplers().size(); i++) {
            appendSampler(jmx, unit.samplers().get(i), fileNames, globalConfig(def), fixedTpsMode(mode),
                    i == 0 ? timer : null);
        }
        appendThinkTime(jmx, thinkTimeMs);
    }

    /**
     * STEPPED 堆叠线程组渲染：按阶梯表展开 N 段，
     * 每段渲染独立 ThreadGroup（props 前缀 tg{i}）+ 子 hashTree（TPS 单位时首采样器含 ConstantThroughputTimer）
     *
     * @param jmx       JMX 输出缓冲
     * @param def       表单场景定义
     * @param samplers  串行组采样器列表
     * @param fileNames CSV 引用的文件ID → 原始文件名映射
     * @param config    阶梯模式参数
     */
    private void appendSteppedGroups(StringBuilder jmx, ScriptFormRequest.FormDef def,
                                     List<ScriptFormRequest.Sampler> samplers,
                                     Map<Long, String> fileNames, TaskCreateRequest.Config config) {
        List<SteppedPlan.Segment> segments = SteppedPlan.expand(config.getStart(), config.getStep(),
                config.getStepSeconds(), config.getPeak(), config.getPeakSeconds());
        int rampup = config.getRampupSeconds() == null ? 30 : config.getRampupSeconds();
        boolean tpsUnit = "TPS".equalsIgnoreCase(config.getUnit());
        for (int i = 0; i < segments.size(); i++) {
            SteppedPlan.Segment segment = segments.get(i);
            // TPS 单位：线程数 = 该段 TPS×2（上限 2000）；THREADS 单位：线程数 = 该段增量
            int threads = tpsUnit
                    ? TpsCapacity.defaultThreads(segment.getValue(), config.getExpectedResponseMs())
                    : segment.getValue();
            appendPropertyThreadGroup(jmx, def, "tg" + i, threads, rampup,
                    segment.getDelaySeconds(), segment.getDurationSeconds());
            jmx.append("      <hashTree>\n");
            TimerSpec timer = tpsUnit
                    ? new TimerSpec("tg" + i, (long) segment.getValue() * 60 / (double) threads) : null;
            appendSamplers(jmx, samplers, def.getThinkTimeMs(), fileNames, def, "STEPPED", timer);
            jmx.append("      </hashTree>\n");
        }
    }

    /**
     * 追加 CONCURRENT 线程组：沿用既有属性名 threads/rampup/duration（向后兼容 M2）
     *
     * @param jmx      JMX 输出缓冲
     * @param def      表单场景定义
     * @param threads  默认并发线程数
     * @param rampup   默认爬坡秒数
     * @param duration 默认持续秒数
     */
    private void appendConcurrentThreadGroup(StringBuilder jmx, ScriptFormRequest.FormDef def,
                                             int threads, int rampup, int duration) {
        String tgName = StringUtils.hasText(def.getThreadGroupName()) ? def.getThreadGroupName() : "Thread Group";
        jmx.append("    <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" testname=\"")
                .append(escapeAttr(tgName)).append("\" enabled=\"true\">\n");
        jmx.append("      <stringProp name=\"ThreadGroup.on_sample_error\">continue</stringProp>\n");
        appendLoopController(jmx);
        jmx.append("      <stringProp name=\"ThreadGroup.num_threads\">${__P(threads,").append(threads).append(")}</stringProp>\n");
        jmx.append("      <stringProp name=\"ThreadGroup.ramp_time\">${__P(rampup,").append(rampup).append(")}</stringProp>\n");
        jmx.append("      <boolProp name=\"ThreadGroup.scheduler\">true</boolProp>\n");
        jmx.append("      <stringProp name=\"ThreadGroup.duration\">${__P(duration,").append(duration).append(")}</stringProp>\n");
        jmx.append("      <stringProp name=\"ThreadGroup.delay\">${__P(delay,0)}</stringProp>\n");
        jmx.append("      <boolProp name=\"ThreadGroup.same_user_on_next_iteration\">true</boolProp>\n");
        jmx.append("    </ThreadGroup>\n");
    }

    /**
     * 追加属性化线程组：num_threads/rampup/duration/delay 全部经 __P({prefix}.xxx) 读取，
     * 用于 FIXED_TPS（prefix=tg0）与 STEPPED 堆叠段（prefix=tg{i}）
     *
     * @param jmx      JMX 输出缓冲
     * @param def      表单场景定义
     * @param prefix   属性前缀（tg0/tg1...）
     * @param threads  默认线程数
     * @param rampup   默认爬坡秒数
     * @param delay    默认启动延迟秒数
     * @param duration 默认持续秒数
     */
    private void appendPropertyThreadGroup(StringBuilder jmx, ScriptFormRequest.FormDef def, String prefix,
                                           int threads, int rampup, int delay, int duration) {
        String tgName = StringUtils.hasText(def.getThreadGroupName()) ? def.getThreadGroupName() : "Thread Group";
        appendNamedPropertyThreadGroup(jmx, tgName + "-" + prefix, prefix, threads, rampup, delay, duration);
    }

    /**
     * 追加属性化线程组（指定显示名）：num_threads/rampup/duration/delay 全部经 __P({prefix}.xxx) 读取，
     * 用于多单元渲染（prefix=tg{u}/tg{seg}x{u}）
     *
     * @param jmx         JMX 输出缓冲
     * @param displayName 线程组 testname
     * @param prefix      属性前缀
     * @param threads     默认线程数
     * @param rampup      默认爬坡秒数
     * @param delay       默认启动延迟秒数
     * @param duration    默认持续秒数
     */
    private void appendNamedPropertyThreadGroup(StringBuilder jmx, String displayName, String prefix,
                                                int threads, int rampup, int delay, int duration) {
        jmx.append("    <ThreadGroup guiclass=\"ThreadGroupGui\" testclass=\"ThreadGroup\" testname=\"")
                .append(escapeAttr(displayName)).append("\" enabled=\"true\">\n");
        jmx.append("      <stringProp name=\"ThreadGroup.on_sample_error\">continue</stringProp>\n");
        appendLoopController(jmx);
        jmx.append("      <stringProp name=\"ThreadGroup.num_threads\">${__P(")
                .append(prefix).append(".threads,").append(threads).append(")}</stringProp>\n");
        jmx.append("      <stringProp name=\"ThreadGroup.ramp_time\">${__P(")
                .append(prefix).append(".rampup,").append(rampup).append(")}</stringProp>\n");
        jmx.append("      <boolProp name=\"ThreadGroup.scheduler\">true</boolProp>\n");
        jmx.append("      <stringProp name=\"ThreadGroup.duration\">${__P(")
                .append(prefix).append(".duration,").append(duration).append(")}</stringProp>\n");
        jmx.append("      <stringProp name=\"ThreadGroup.delay\">${__P(")
                .append(prefix).append(".delay,").append(delay).append(")}</stringProp>\n");
        jmx.append("      <boolProp name=\"ThreadGroup.same_user_on_next_iteration\">true</boolProp>\n");
        jmx.append("    </ThreadGroup>\n");
    }

    /**
     * 追加线程组内循环控制器（无限循环，由调度器时长控制结束）
     *
     * @param jmx JMX 输出缓冲
     */
    private void appendLoopController(StringBuilder jmx) {
        jmx.append("      <elementProp name=\"ThreadGroup.main_controller\" elementType=\"LoopController\" ")
                .append("guiclass=\"LoopControlPanel\" testclass=\"LoopController\" testname=\"Loop Controller\" enabled=\"true\">\n");
        jmx.append("        <boolProp name=\"LoopController.continue_forever\">false</boolProp>\n");
        jmx.append("        <stringProp name=\"LoopController.loops\">-1</stringProp>\n");
        jmx.append("      </elementProp>\n");
    }

    /**
     * 追加 Constant Throughput Timer（全部 TPS 类压测通用：FIXED_TPS 与 STEPPED-TPS）：
     * calcMode=0（每线程独立限速，无跨线程组共享状态），throughput 为「每线程每分钟样本数」，
     * 由调用方按 线程组吞吐(samples/min) ÷ 组线程数 换算——各段/各单元线程组互不干扰，
     * 堆叠段各自独立达到增量吞吐（实测 calcMode=1 的分母取全局活跃线程，多段互相压低导致阶梯不上升）
     *
     * @param jmx                  JMX 输出缓冲
     * @param prefix               属性前缀（tg0/tg{seg}x{u}）
     * @param perThreadSamplesPerMin __P 默认值（samples/min/线程，单节点直跑时= 组吞吐÷组线程数）
     */
    private void appendConstantThroughputTimer(StringBuilder jmx, String prefix, double perThreadSamplesPerMin) {
        jmx.append("      <ConstantThroughputTimer guiclass=\"TestBeanGUI\" testclass=\"ConstantThroughputTimer\" ")
                .append("testname=\"CTT-").append(escapeAttr(prefix)).append("\" enabled=\"true\">\n");
        jmx.append("        <stringProp name=\"throughput\">${__P(")
                .append(prefix).append(".perThreadPerMin,").append(perThreadSamplesPerMin).append(")}</stringProp>\n");
        jmx.append("        <intProp name=\"calcMode\">0</intProp>\n");
        jmx.append("      </ConstantThroughputTimer>\n");
        jmx.append("      <hashTree/>\n");
    }

    /**
     * 追加全部采样器与思考时间（兼容分支复用：单组采样器列表）
     *
     * @param jmx         JMX 输出缓冲
     * @param samplers    采样器列表
     * @param thinkTimeMs 思考时间（毫秒）
     * @param fileNames   CSV 引用的文件ID → 原始文件名映射
     * @param def         表单场景定义（取全局配置用于相对路径补全）
     * @param mode        压测模式（FIXED_TPS 时串行链路内渲染接口级吞吐量控制器漏斗）
     */
    private void appendSamplers(StringBuilder jmx, List<ScriptFormRequest.Sampler> samplers,
                                Long thinkTimeMs, Map<Long, String> fileNames,
                                ScriptFormRequest.FormDef def, String mode) {
        appendSamplers(jmx, samplers, thinkTimeMs, fileNames, def, mode, null);
    }

    private void appendSamplers(StringBuilder jmx, List<ScriptFormRequest.Sampler> samplers,
                                Long thinkTimeMs, Map<Long, String> fileNames,
                                ScriptFormRequest.FormDef def, String mode, TimerSpec timer) {
        for (int i = 0; i < samplers.size(); i++) {
            appendSampler(jmx, samplers.get(i), fileNames, globalConfig(def), fixedTpsMode(mode),
                    i == 0 ? timer : null);
        }
        appendThinkTime(jmx, thinkTimeMs);
    }

    /**
     * 读取表单定义的全局配置（null 安全，旧数据无 config 字段返回 null）
     *
     * @param def 表单场景定义
     * @return 全局配置（可空）
     */
    private static ScriptFormRequest.Config globalConfig(ScriptFormRequest.FormDef def) {
        return def == null ? null : def.getConfig();
    }

    /**
     * 追加 TestPlan 节点：JMeter 引擎要求测试计划根元素存在，否则 NonGUI 启动失败；
     * 全局自定义变量注入 TestPlan.user_defined_variables（UDV），接口任意位置 ${name} 引用
     *
     * @param jmx JMX 输出缓冲
     * @param def 表单场景定义（取全局配置的自定义变量）
     */
    private void appendTestPlan(StringBuilder jmx, ScriptFormRequest.FormDef def) {
        List<ScriptFormRequest.Variable> variables = def != null && def.getConfig() != null
                ? def.getConfig().getVariables() : null;
        jmx.append("    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"PerPress Test Plan\" enabled=\"true\">\n");
        jmx.append("      <stringProp name=\"TestPlan.comments\"></stringProp>\n");
        jmx.append("      <boolProp name=\"TestPlan.functional_mode\">false</boolProp>\n");
        jmx.append("      <boolProp name=\"TestPlan.tearDown_on_shutdown\">true</boolProp>\n");
        jmx.append("      <boolProp name=\"TestPlan.serialize_threadgroups\">false</boolProp>\n");
        jmx.append("      <elementProp name=\"TestPlan.user_defined_variables\" elementType=\"Arguments\" ")
                .append("guiclass=\"ArgumentsPanel\" testclass=\"Arguments\" testname=\"User Defined Variables\" enabled=\"true\">\n");
        if (variables == null || variables.isEmpty()) {
            jmx.append("        <collectionProp name=\"Arguments.arguments\"/>\n");
        } else {
            jmx.append("        <collectionProp name=\"Arguments.arguments\">\n");
            for (ScriptFormRequest.Variable variable : variables) {
                if (variable == null || !StringUtils.hasText(variable.getName())) {
                    continue;
                }
                jmx.append("          <elementProp name=\"").append(escapeAttr(variable.getName().trim()))
                        .append("\" elementType=\"Argument\">\n");
                jmx.append("            <stringProp name=\"Argument.name\">")
                        .append(escapeText(variable.getName().trim())).append("</stringProp>\n");
                jmx.append("            <stringProp name=\"Argument.value\">")
                        .append(escapeText(variable.getValue() == null ? "" : variable.getValue()))
                        .append("</stringProp>\n");
                jmx.append("            <stringProp name=\"Argument.metadata\">=</stringProp>\n");
                jmx.append("          </elementProp>\n");
            }
            jmx.append("        </collectionProp>\n");
        }
        jmx.append("      </elementProp>\n");
        jmx.append("      <stringProp name=\"TestPlan.user_define_classpath\"></stringProp>\n");
        jmx.append("    </TestPlan>\n");
    }

    /**
     * 追加单个采样器：CSVDataSet 列表 + HTTPSamplerProxy + HeaderManager + ResponseAssertion + 参数提取器；
     * FIXED_TPS 模式且配置了接口流量占比（0<p<100）时，采样器包一层 ThroughputController（百分比模式）
     * 形成串行链路漏斗（如登录100→查价100→下单60→支付30），占比为空/100 时不包裹（结构与旧版一致）
     *
     * @param jmx           JMX 输出缓冲
     * @param sampler       采样器定义
     * @param fileNames     CSV 引用的文件ID → 原始文件名映射
     * @param globalConfig  全局配置（采样器 url 为相对路径时补全协议/域名/端口）
     * @param funnelEnabled 是否启用接口级漏斗（仅 FIXED_TPS 模式为 true）
     */
    private void appendSampler(StringBuilder jmx, ScriptFormRequest.Sampler sampler,
                               Map<Long, String> fileNames, ScriptFormRequest.Config globalConfig,
                               boolean funnelEnabled, TimerSpec timer) {
        String name = StringUtils.hasText(sampler.getName()) ? sampler.getName() : sampler.getUrl();
        if (sampler.getCsvRefs() != null) {
            for (ScriptFormRequest.CsvRef csvRef : sampler.getCsvRefs()) {
                appendCsvDataSet(jmx, csvRef, fileNames);
            }
        }
        // 漏斗条件：FIXED_TPS 模式 + 占比有效（0<p<100；null/100 全量执行不包裹）
        Double percent = sampler.getTrafficPercent();
        boolean funnel = funnelEnabled && percent != null && percent > 0 && percent < 100;
        if (funnel) {
            appendThroughputController(jmx, name, percent);
        }
        appendHttpSampler(jmx, name, sampler, globalConfig);
        jmx.append("      <hashTree>\n");
        if (timer != null) {
            appendConstantThroughputTimer(jmx, timer.prefix(), timer.perThreadSamplesPerMin());
        }
        if (sampler.getHeaders() != null && !sampler.getHeaders().isEmpty()) {
            appendHeaderManager(jmx, sampler.getHeaders());
        }
        if (sampler.getAssertions() != null) {
            for (ScriptFormRequest.Assertion assertion : sampler.getAssertions()) {
                appendResponseAssertion(jmx, assertion);
            }
        }
        if (sampler.getExtractors() != null) {
            for (ScriptFormRequest.Extractor extractor : sampler.getExtractors()) {
                appendExtractor(jmx, extractor);
            }
        }
        jmx.append("      </hashTree>\n");
        if (funnel) {
            // 关闭 ThroughputController 子树（采样器整体作为控制器子节点）
            jmx.append("      </hashTree>\n");
        }
    }

    /**
     * 追加吞吐量控制器（ThroughputController 百分比模式）：仅放行指定百分比的迭代经过其子树，
     * 实现 FIXED_TPS 串行链路的接口级漏斗；perThread=false 全局统计（跨线程累计，比例精确到大数定律）
     *
     * @param jmx     JMX 输出缓冲
     * @param name    采样器名（控制器命名 TC-{name}）
     * @param percent 放行百分比（0<p<100）
     */
    private void appendThroughputController(StringBuilder jmx, String name, double percent) {
        jmx.append("      <ThroughputController guiclass=\"ThroughputControllerGui\" testclass=\"ThroughputController\" ")
                .append("testname=\"TC-").append(escapeAttr(name)).append("\" enabled=\"true\">\n");
        jmx.append("        <boolProp name=\"ThroughputController.perThread\">false</boolProp>\n");
        // style=1 百分比模式（0 为总次数模式）；属性名 percentThroughput（JMeter 5.6 TestBean 定义，
        // 错写成 percent 会被静默忽略，退化为总次数模式导致漏斗失效）
        jmx.append("        <intProp name=\"ThroughputController.style\">1</intProp>\n");
        jmx.append("        <stringProp name=\"ThroughputController.percentThroughput\">").append(percent).append("</stringProp>\n");
        jmx.append("        <stringProp name=\"ThroughputController.maxThroughput\">1</stringProp>\n");
        jmx.append("      </ThroughputController>\n");
        jmx.append("      <hashTree>\n");
    }

    /**
     * 判断压测模式是否为 FIXED_TPS（接口级漏斗仅在该模式渲染；CONCURRENT/STEPPED 保持单元整体占比）
     *
     * @param mode 压测模式
     * @return true 表示 FIXED_TPS
     */
    private static boolean fixedTpsMode(String mode) {
        return "FIXED_TPS".equalsIgnoreCase(mode);
    }

    /** 首个采样器上的业务迭代限速配置。 */
    private record TimerSpec(String prefix, double perThreadSamplesPerMin) {
    }

    /**
     * 追加 CSVDataSet：filename 使用文件原名（Agent 落盘后同名引用），
     * ignoreFirstLine 由 CsvRef 定义渲染（默认 false，true 时跳过参数文件首行）
     *
     * @param jmx       JMX 输出缓冲
     * @param csvRef    CSV 引用定义
     * @param fileNames CSV 引用的文件ID → 原始文件名映射
     */
    private void appendCsvDataSet(StringBuilder jmx, ScriptFormRequest.CsvRef csvRef, Map<Long, String> fileNames) {
        String fileName = fileNames.get(csvRef.getFileId());
        if (!StringUtils.hasText(fileName)) {
            throw new BizException("CSV引用的文件不存在：fileId=" + csvRef.getFileId());
        }
        String delimiter = StringUtils.hasText(csvRef.getDelimiter()) ? csvRef.getDelimiter() : ",";
        boolean ignoreFirstLine = csvRef.getIgnoreFirstLine() != null && csvRef.getIgnoreFirstLine();
        boolean recycle = csvRef.getRecycle() == null || csvRef.getRecycle();
        String shareMode = StringUtils.hasText(csvRef.getShareMode()) ? csvRef.getShareMode() : "shareMode.all";
        jmx.append("      <CSVDataSet guiclass=\"TestBeanGUI\" testclass=\"CSVDataSet\" testname=\"")
                .append(escapeAttr(fileName)).append("\" enabled=\"true\">\n");
        jmx.append("        <stringProp name=\"delimiter\">").append(escapeText(delimiter)).append("</stringProp>\n");
        jmx.append("        <stringProp name=\"filename\">").append(escapeText(fileName)).append("</stringProp>\n");
        jmx.append("        <boolProp name=\"ignoreFirstLine\">").append(ignoreFirstLine).append("</boolProp>\n");
        jmx.append("        <stringProp name=\"variableNames\">")
                .append(escapeText(csvRef.getVarNames() == null ? "" : csvRef.getVarNames())).append("</stringProp>\n");
        jmx.append("        <boolProp name=\"recycle\">").append(recycle).append("</boolProp>\n");
        jmx.append("        <boolProp name=\"stopThread\">false</boolProp>\n");
        jmx.append("        <stringProp name=\"shareMode\">").append(escapeText(shareMode)).append("</stringProp>\n");
        jmx.append("      </CSVDataSet>\n");
        jmx.append("      <hashTree/>\n");
    }

    /**
     * 追加 HTTPSamplerProxy：解析 url 得到协议/域名/端口/路径，body 以原始文本方式渲染。
     * url 为相对路径（无 scheme）时用全局环境配置补全为绝对地址——换环境只改全局配置，不用逐接口修改
     *
     * @param jmx           JMX 输出缓冲
     * @param name          采样器名称
     * @param sampler       采样器定义
     * @param globalConfig  全局配置（协议/域名/端口）
     */
    private void appendHttpSampler(StringBuilder jmx, String name, ScriptFormRequest.Sampler sampler,
                                   ScriptFormRequest.Config globalConfig) {
        String rawUrl = sampler.getUrl().trim();
        // 相对路径 → 全局环境补全（protocol://host[:port]/path）；未配置全局域名则要求完整 URL
        if (!rawUrl.contains("://")) {
            if (globalConfig == null || !StringUtils.hasText(globalConfig.getHost())) {
                throw new BizException("接口 url 需为完整地址（http:// 开头），或在全局配置中填写域名：" + rawUrl);
            }
            StringBuilder absolute = new StringBuilder();
            absolute.append(StringUtils.hasText(globalConfig.getProtocol()) ? globalConfig.getProtocol().trim() : "http");
            absolute.append("://").append(globalConfig.getHost().trim());
            if (globalConfig.getPort() != null) {
                absolute.append(':').append(globalConfig.getPort());
            }
            if (!rawUrl.startsWith("/")) {
                absolute.append('/');
            }
            absolute.append(rawUrl);
            rawUrl = absolute.toString();
        }
        String protocol;
        String port;
        String path;
        String host;
        if (rawUrl.contains("${")) {
            // 含变量 URL（如 ?username=${username}）：java.net.URI 视 {} 为非法字符，
            // 结构校验时先替换占位符；path+query 保留原始串（JMeter 运行时解析变量）
            String sanitized = rawUrl.replaceAll("\\$\\{[^}]*}", "PPV");
            URI check;
            try {
                check = URI.create(sanitized);
            } catch (IllegalArgumentException e) {
                throw new BizException(BAD_URL + rawUrl);
            }
            if (!StringUtils.hasText(check.getHost())) {
                throw new BizException(BAD_URL + rawUrl);
            }
            protocol = StringUtils.hasText(check.getScheme()) ? check.getScheme() : "http";
            host = check.getHost();
            port = check.getPort() == -1 ? "" : String.valueOf(check.getPort());
            int schemeEnd = rawUrl.indexOf("://");
            int pathStart = rawUrl.indexOf('/', schemeEnd + 3);
            path = pathStart >= 0 ? rawUrl.substring(pathStart) : "/";
        } else {
            URI uri;
            try {
                uri = URI.create(rawUrl);
            } catch (IllegalArgumentException e) {
                throw new BizException(BAD_URL + rawUrl);
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new BizException(BAD_URL + rawUrl);
            }
            protocol = StringUtils.hasText(uri.getScheme()) ? uri.getScheme() : "http";
            host = uri.getHost();
            port = uri.getPort() == -1 ? "" : String.valueOf(uri.getPort());
            path = StringUtils.hasText(uri.getPath()) ? uri.getPath() : "/";
            if (StringUtils.hasText(uri.getQuery())) {
                path = path + "?" + uri.getQuery();
            }
        }
        String method = StringUtils.hasText(sampler.getMethod()) ? sampler.getMethod().toUpperCase() : "GET";
        boolean hasBody = StringUtils.hasText(sampler.getBody());
        jmx.append("      <HTTPSamplerProxy guiclass=\"HttpTestSampleGui\" testclass=\"HTTPSamplerProxy\" testname=\"")
                .append(escapeAttr(name)).append("\" enabled=\"true\">\n");
        jmx.append("        <boolProp name=\"HTTPSampler.postBodyRaw\">").append(hasBody).append("</boolProp>\n");
        jmx.append("        <elementProp name=\"HTTPsampler.Arguments\" elementType=\"Arguments\" ")
                .append("guiclass=\"HTTPArgumentsPanel\" testclass=\"Arguments\" testname=\"User Defined Variables\" enabled=\"true\">\n");
        jmx.append("          <collectionProp name=\"Arguments.arguments\">\n");
        if (hasBody) {
            jmx.append("            <elementProp name=\"\" elementType=\"HTTPArgument\">\n");
            jmx.append("              <boolProp name=\"HTTPArgument.always_encode\">false</boolProp>\n");
            jmx.append("              <stringProp name=\"Argument.value\">").append(escapeText(sampler.getBody())).append("</stringProp>\n");
            jmx.append("              <stringProp name=\"Argument.metadata\">=</stringProp>\n");
            jmx.append("            </elementProp>\n");
        }
        jmx.append("          </collectionProp>\n");
        jmx.append("        </elementProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.domain\">").append(escapeText(host)).append("</stringProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.port\">").append(escapeText(port)).append("</stringProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.protocol\">").append(escapeText(protocol)).append("</stringProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.contentEncoding\">UTF-8</stringProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.path\">").append(escapeText(path)).append("</stringProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.method\">").append(escapeText(method)).append("</stringProp>\n");
        jmx.append("        <boolProp name=\"HTTPSampler.follow_redirects\">true</boolProp>\n");
        jmx.append("        <boolProp name=\"HTTPSampler.auto_redirects\">false</boolProp>\n");
        jmx.append("        <boolProp name=\"HTTPSampler.use_keepalive\">true</boolProp>\n");
        jmx.append("        <boolProp name=\"HTTPSampler.DO_MULTIPART_POST\">false</boolProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.connect_timeout\">5000</stringProp>\n");
        jmx.append("        <stringProp name=\"HTTPSampler.response_timeout\">30000</stringProp>\n");
        jmx.append("      </HTTPSamplerProxy>\n");
    }

    /**
     * 追加 HeaderManager：自定义请求头列表
     *
     * @param jmx     JMX 输出缓冲
     * @param headers 请求头列表
     */
    private void appendHeaderManager(StringBuilder jmx, List<ScriptFormRequest.Header> headers) {
        jmx.append("        <HeaderManager guiclass=\"HeaderPanel\" testclass=\"HeaderManager\" ")
                .append("testname=\"Headers\" enabled=\"true\">\n");
        jmx.append("          <collectionProp name=\"HeaderManager.headers\">\n");
        for (ScriptFormRequest.Header header : headers) {
            jmx.append("            <elementProp name=\"\" elementType=\"Header\">\n");
            jmx.append("              <stringProp name=\"Header.name\">").append(escapeText(header.getK())).append("</stringProp>\n");
            jmx.append("              <stringProp name=\"Header.value\">").append(escapeText(header.getV())).append("</stringProp>\n");
            jmx.append("            </elementProp>\n");
        }
        jmx.append("          </collectionProp>\n");
        jmx.append("        </HeaderManager>\n");
        jmx.append("        <hashTree/>\n");
    }

    /**
     * 追加 ResponseAssertion：TEXT 匹配响应文本包含（Contains），CODE 匹配响应码相等（Equals）
     *
     * @param jmx       JMX 输出缓冲
     * @param assertion 断言定义
     */
    private void appendResponseAssertion(StringBuilder jmx, ScriptFormRequest.Assertion assertion) {
        String type = StringUtils.hasText(assertion.getType()) ? assertion.getType().toUpperCase() : "TEXT";
        String testField = "TEXT".equals(type) ? "Assertion.response_data" : "Assertion.response_code";
        int testType = "TEXT".equals(type) ? 2 : 8;
        jmx.append("        <ResponseAssertion guiclass=\"AssertionGui\" testclass=\"ResponseAssertion\" ")
                .append("testname=\"RA-").append(escapeAttr(type)).append("\" enabled=\"true\">\n");
        jmx.append("          <collectionProp name=\"Asserion.test_strings\">\n");
        jmx.append("            <stringProp name=\"12552750\">").append(escapeText(assertion.getExpect())).append("</stringProp>\n");
        jmx.append("          </collectionProp>\n");
        jmx.append("          <stringProp name=\"Assertion.test_field\">").append(testField).append("</stringProp>\n");
        jmx.append("          <boolProp name=\"Assertion.assume_success\">false</boolProp>\n");
        jmx.append("          <intProp name=\"Assertion.test_type\">").append(testType).append("</intProp>\n");
        jmx.append("        </ResponseAssertion>\n");
        jmx.append("        <hashTree/>\n");
    }

    /**
     * 追加参数提取器：按类型分派渲染（REGEX/JSON/BOUNDARY），提取的变量供串行组内后续接口 ${refName} 引用
     *
     * @param jmx       JMX 输出缓冲
     * @param extractor 提取器定义
     */
    private void appendExtractor(StringBuilder jmx, ScriptFormRequest.Extractor extractor) {
        String type = StringUtils.hasText(extractor.getType()) ? extractor.getType().toUpperCase() : "REGEX";
        if (!StringUtils.hasText(extractor.getRefName())) {
            throw new BizException("参数提取器 refName（引用名）不能为空");
        }
        if (!StringUtils.hasText(extractor.getExpression())) {
            throw new BizException("参数提取器 " + extractor.getRefName() + " 的提取表达式不能为空");
        }
        switch (type) {
            case "JSON" -> appendJsonExtractor(jmx, extractor);
            case "BOUNDARY" -> appendBoundaryExtractor(jmx, extractor);
            default -> appendRegexExtractor(jmx, extractor);
        }
    }

    /**
     * RegexExtractor 的 useHeaders 属性取值映射（JMeter 源码 USE_HDRS_VALUES 语义）：
     * 空/false=响应体，true=响应头，request headers=请求头，url=请求 URL，code=响应码，message=响应消息
     *
     * @param source 提取来源（RESPONSE_BODY/RESPONSE_HEADERS/REQUEST_HEADERS/URL/RESPONSE_CODE/RESPONSE_MESSAGE）
     * @return RegexExtractor/BoundaryExtractor 的 useHeaders 取值
     */
    private static String regexUseHeadersValue(String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        return switch (source.toUpperCase()) {
            case "RESPONSE_HEADERS" -> "true";
            case "REQUEST_HEADERS" -> "request headers";
            case "URL" -> "url";
            case "RESPONSE_CODE" -> "code";
            case "RESPONSE_MESSAGE" -> "message";
            default -> "";
        };
    }

    /**
     * 追加正则提取器（RegexExtractor）：expression 为正则（配合分组与模板取值），默认模板 $1$
     *
     * @param jmx       JMX 输出缓冲
     * @param extractor 提取器定义
     */
    private void appendRegexExtractor(StringBuilder jmx, ScriptFormRequest.Extractor extractor) {
        String template = StringUtils.hasText(extractor.getTemplate()) ? extractor.getTemplate() : "$1$";
        int matchNumber = extractor.getMatchNumber() == null ? 1 : extractor.getMatchNumber();
        String defaultValue = extractor.getDefaultValue() == null ? "" : extractor.getDefaultValue();
        jmx.append("        <RegexExtractor guiclass=\"RegexExtractorGui\" testclass=\"RegexExtractor\" ")
                .append("testname=\"EX-").append(escapeAttr(extractor.getRefName())).append("\" enabled=\"true\">\n");
        jmx.append("          <stringProp name=\"RegexExtractor.useHeaders\">")
                .append(escapeText(regexUseHeadersValue(extractor.getSource()))).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"RegexExtractor.refname\">")
                .append(escapeText(extractor.getRefName())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"RegexExtractor.regex\">")
                .append(escapeText(extractor.getExpression())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"RegexExtractor.template\">")
                .append(escapeText(template)).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"RegexExtractor.default\">")
                .append(escapeText(defaultValue)).append("</stringProp>\n");
        jmx.append("          <boolProp name=\"RegexExtractor.default_empty_value\">false</boolProp>\n");
        jmx.append("          <stringProp name=\"RegexExtractor.match_number\">")
                .append(matchNumber).append("</stringProp>\n");
        jmx.append("        </RegexExtractor>\n");
        jmx.append("        <hashTree/>\n");
    }

    /**
     * 追加 JSON 提取器（JSONPostProcessor）：expression 为 JSONPath（如 $.data.token）；
     * JSON 提取器的来源不支持 URL（JMeter 5.6 SOURCE 取值集不含 url）
     *
     * @param jmx       JMX 输出缓冲
     * @param extractor 提取器定义
     */
    private void appendJsonExtractor(StringBuilder jmx, ScriptFormRequest.Extractor extractor) {
        if ("URL".equalsIgnoreCase(extractor.getSource())) {
            throw new BizException("JSON 提取器 " + extractor.getRefName() + " 不支持从 URL 提取，请改用正则提取器");
        }
        int matchNumber = extractor.getMatchNumber() == null ? 1 : extractor.getMatchNumber();
        String defaultValue = extractor.getDefaultValue() == null ? "" : extractor.getDefaultValue();
        String source = StringUtils.hasText(extractor.getSource()) ? extractor.getSource().toLowerCase() : "body";
        jmx.append("        <JSONPostProcessor guiclass=\"JSONPostProcessorGui\" testclass=\"JSONPostProcessor\" ")
                .append("testname=\"EX-").append(escapeAttr(extractor.getRefName())).append("\" enabled=\"true\">\n");
        jmx.append("          <stringProp name=\"JSONPostProcessor.referenceNames\">")
                .append(escapeText(extractor.getRefName())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"JSONPostProcessor.jsonPathExprs\">")
                .append(escapeText(extractor.getExpression())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"JSONPostProcessor.match_numbers\">")
                .append(matchNumber).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"JSONPostProcessor.defaultValues\">")
                .append(escapeText(defaultValue)).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"JSONPostProcessor.SOURCE\">")
                .append(escapeText(source)).append("</stringProp>\n");
        jmx.append("        </JSONPostProcessor>\n");
        jmx.append("        <hashTree/>\n");
    }

    /**
     * 追加边界提取器（BoundaryExtractor）：expression 为左边界、rightBoundary 为右边界，两者之间为提取值
     *
     * @param jmx       JMX 输出缓冲
     * @param extractor 提取器定义
     */
    private void appendBoundaryExtractor(StringBuilder jmx, ScriptFormRequest.Extractor extractor) {
        if (!StringUtils.hasText(extractor.getRightBoundary())) {
            throw new BizException("边界提取器 " + extractor.getRefName() + " 的右边界不能为空");
        }
        int matchNumber = extractor.getMatchNumber() == null ? 1 : extractor.getMatchNumber();
        String defaultValue = extractor.getDefaultValue() == null ? "" : extractor.getDefaultValue();
        jmx.append("        <BoundaryExtractor guiclass=\"BoundaryExtractorGui\" testclass=\"BoundaryExtractor\" ")
                .append("testname=\"EX-").append(escapeAttr(extractor.getRefName())).append("\" enabled=\"true\">\n");
        jmx.append("          <stringProp name=\"BoundaryExtractor.lboundary\">")
                .append(escapeText(extractor.getExpression())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"BoundaryExtractor.rboundary\">")
                .append(escapeText(extractor.getRightBoundary())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"BoundaryExtractor.refname\">")
                .append(escapeText(extractor.getRefName())).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"BoundaryExtractor.match_number\">")
                .append(matchNumber).append("</stringProp>\n");
        jmx.append("          <boolProp name=\"BoundaryExtractor.default_empty_value\">false</boolProp>\n");
        jmx.append("          <stringProp name=\"BoundaryExtractor.default\">")
                .append(escapeText(defaultValue)).append("</stringProp>\n");
        jmx.append("          <stringProp name=\"BoundaryExtractor.useHeaders\">")
                .append(escapeText(regexUseHeadersValue(extractor.getSource()))).append("</stringProp>\n");
        jmx.append("        </BoundaryExtractor>\n");
        jmx.append("        <hashTree/>\n");
    }

    /**
     * 追加线程组级 ConstantTimer：thinkTimeMs 大于 0 时渲染，作用于作用域内全部采样器
     *
     * @param jmx         JMX 输出缓冲
     * @param thinkTimeMs 思考时间（毫秒）
     */
    private void appendThinkTime(StringBuilder jmx, Long thinkTimeMs) {
        if (thinkTimeMs == null || thinkTimeMs <= 0) {
            return;
        }
        jmx.append("      <ConstantTimer guiclass=\"ConstantTimerGui\" testclass=\"ConstantTimer\" ")
                .append("testname=\"Think Time\" enabled=\"true\">\n");
        jmx.append("        <stringProp name=\"ConstantTimer.delay\">").append(thinkTimeMs).append("</stringProp>\n");
        jmx.append("      </ConstantTimer>\n");
        jmx.append("      <hashTree/>\n");
    }

    /**
     * XML 文本节点转义（& < >）
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private String escapeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * XML 属性值转义（& < > " '）
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private String escapeAttr(String text) {
        return escapeText(text).replace("\"", "&quot;").replace("'", "&apos;");
    }
}
