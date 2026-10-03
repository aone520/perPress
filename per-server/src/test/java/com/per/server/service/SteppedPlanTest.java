package com.per.server.service;

import com.per.server.dto.ScriptFormRequest;
import com.per.server.dto.TaskCreateRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶梯表展开与堆叠 TG 参数自证测试：
 * 覆盖整除阶梯（10→100）、非整除最后一段部分增量、peak==start 单段，
 * 以及 buildWithMode 生成的 JMX 中堆叠 TG 属性（threads/delay/duration/tpsPerMin）与 PTT 形态
 */
class SteppedPlanTest {

    /**
     * 构造含单个 GET 采样器的最小表单定义（渲染器冒烟用）
     *
     * @return 表单场景定义
     */
    private ScriptFormRequest.FormDef minimalDef() {
        ScriptFormRequest.FormDef def = new ScriptFormRequest.FormDef();
        def.setThreadGroupName("阶梯组");
        ScriptFormRequest.Sampler sampler = new ScriptFormRequest.Sampler();
        sampler.setName("s1");
        sampler.setMethod("GET");
        sampler.setUrl("http://perpress.example.com/api/x");
        def.setSamplers(List.of(sampler));
        return def;
    }

    /**
     * 构造 STEPPED 模式参数
     *
     * @param unit 阶梯单位（THREADS/TPS）
     * @return 模式参数
     */
    private TaskCreateRequest.Config steppedConfig(String unit) {
        TaskCreateRequest.Config config = new TaskCreateRequest.Config();
        config.setUnit(unit);
        config.setStart(10);
        config.setStep(10);
        config.setStepSeconds(120);
        config.setPeak(100);
        config.setPeakSeconds(600);
        return config;
    }

    /**
     * 验证整除阶梯展开（start=10,step=10,stepSeconds=120,peak=100,peakSeconds=600）：
     * 10 段、总时长 1680s；段0(10,0,1680)、段5(10,600,1080)、段9(10,1080,600)
     */
    @Test
    void testExpandDivisibleLadder() {
        List<SteppedPlan.Segment> segments = SteppedPlan.expand(10, 10, 120, 100, 600);
        assertEquals(10, segments.size());
        SteppedPlan.Segment seg0 = segments.get(0);
        assertEquals(10, seg0.getValue());
        assertEquals(0, seg0.getDelaySeconds());
        assertEquals(1680, seg0.getDurationSeconds());
        SteppedPlan.Segment seg5 = segments.get(5);
        assertEquals(10, seg5.getValue());
        assertEquals(600, seg5.getDelaySeconds());
        assertEquals(1080, seg5.getDurationSeconds());
        SteppedPlan.Segment seg9 = segments.get(9);
        assertEquals(10, seg9.getValue());
        assertEquals(1080, seg9.getDelaySeconds());
        assertEquals(600, seg9.getDurationSeconds());
        assertEquals(1680, SteppedPlan.totalDuration(10, 10, 120, 100, 600));
    }

    /**
     * 验证非整除阶梯（start=10,step=40,stepSeconds=60,peak=100,peakSeconds=300）：
     * 4 段（负载 10/40/40/10），最后一段为部分增量；段3(10,180,300)，总时长 480s
     */
    @Test
    void testExpandPartialLastStep() {
        List<SteppedPlan.Segment> segments = SteppedPlan.expand(10, 40, 60, 100, 300);
        assertEquals(4, segments.size());
        assertEquals(10, segments.get(0).getValue());
        assertEquals(40, segments.get(1).getValue());
        assertEquals(40, segments.get(2).getValue());
        SteppedPlan.Segment last = segments.get(3);
        assertEquals(10, last.getValue());
        assertEquals(180, last.getDelaySeconds());
        assertEquals(300, last.getDurationSeconds());
        assertEquals(480, SteppedPlan.totalDuration(10, 40, 60, 100, 300));
    }

    /**
     * 验证 peak==start 退化场景：仅 1 段，持续 peakSeconds
     */
    @Test
    void testExpandPeakEqualsStart() {
        List<SteppedPlan.Segment> segments = SteppedPlan.expand(50, 10, 60, 50, 120);
        assertEquals(1, segments.size());
        assertEquals(50, segments.get(0).getValue());
        assertEquals(0, segments.get(0).getDelaySeconds());
        assertEquals(120, segments.get(0).getDurationSeconds());
    }

    /**
     * 验证 STEPPED-THREADS 的 JMX 形态：堆叠 10 个属性化 ThreadGroup（tg0~tg9），
     * 每段 delay/duration 按阶梯表推导、无吞吐量定时器
     */
    @Test
    void testSteppedThreadsJmx() {
        String jmx = new FormScriptJmxBuilder()
                .buildWithMode(minimalDef(), Map.of(), "STEPPED", steppedConfig("THREADS"));
        for (int i = 0; i < 10; i++) {
            assertTrue(jmx.contains("${__P(tg" + i + ".threads,"), "缺少 tg" + i + ".threads");
            assertTrue(jmx.contains("${__P(tg" + i + ".delay,"), "缺少 tg" + i + ".delay");
            assertTrue(jmx.contains("${__P(tg" + i + ".duration,"), "缺少 tg" + i + ".duration");
        }
        assertTrue(jmx.contains("${__P(tg0.duration,1680)}"));
        assertTrue(jmx.contains("${__P(tg9.delay,1080)}"));
        assertTrue(jmx.contains("${__P(tg9.duration,600)}"));
        assertFalse(jmx.contains("PreciseThroughputTimer"));
    }

    /**
     * 验证 STEPPED-TPS 的 JMX 形态：每段堆叠 TG 线程数=段TPS×2（上限2000）并挂
     * ConstantThroughputTimer（挂首个采样器，每次业务迭代只限速一次）
     */
    @Test
    void testSteppedTpsJmx() {
        String jmx = new FormScriptJmxBuilder()
                .buildWithMode(minimalDef(), Map.of(), "STEPPED", steppedConfig("TPS"));
        assertTrue(jmx.contains("${__P(tg0.threads,2)}"));
        assertTrue(jmx.contains("${__P(tg9.threads,2)}"));
        assertTrue(jmx.contains("<ConstantThroughputTimer guiclass=\"TestBeanGUI\" testclass=\"ConstantThroughputTimer\""));
        assertTrue(jmx.contains("${__P(tg0.perThreadPerMin,300.0)}"));
        assertTrue(jmx.contains("${__P(tg9.perThreadPerMin,300.0)}"));
        assertTrue(jmx.contains("<intProp name=\"calcMode\">0</intProp>"));
    }

    /**
     * 验证 FIXED_TPS 的 JMX 形态：单属性化线程组 + 首采样器 CTT，
     * 线程默认按 TPS 与预期响应时间估算、throughput 默认 tps*60
     */
    @Test
    void testFixedTpsJmx() {
        TaskCreateRequest.Config config = new TaskCreateRequest.Config();
        config.setTps(1500);
        config.setDurationSeconds(120);
        String jmx = new FormScriptJmxBuilder()
                .buildWithMode(minimalDef(), Map.of(), "FIXED_TPS", config);
        // 默认预期RT=100ms并预留50%：1500×0.1×1.5=225
        assertTrue(jmx.contains("${__P(tg0.threads,225)}"));
        assertTrue(jmx.contains("${__P(tg0.duration,120)}"));
        assertTrue(jmx.contains("${__P(tg0.perThreadPerMin,400.0)}"));
        assertTrue(jmx.contains("ConstantThroughputTimer"));
        // 采样器仍在（单 TG 挂全部采样器）
        assertTrue(jmx.contains("HTTPSamplerProxy"));
    }
}
