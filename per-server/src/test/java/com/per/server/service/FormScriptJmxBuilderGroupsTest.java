package com.per.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.per.server.dto.ScriptFormRequest;
import com.per.server.dto.TaskCreateRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 表单编排（groups）与流量占比回归测试：
 * 1）旧结构（无 groups）单串行组渲染的 JMX 与改造前数据库存量版本逐字节一致（兼容硬保证）；
 * 2）多单元（并行接口/多组）渲染：TG 数量、属性前缀、按占比拆分的内嵌默认值；
 * 3）WeightSplitter 拆分余数规则：Σ 结果等于总量、余数补给占比大的单元
 */
class FormScriptJmxBuilderGroupsTest {

    private final FormScriptJmxBuilder builder = new FormScriptJmxBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 本机开发库连接串（docker mysql-container，仅测试用） */
    private static final String JDBC_URL =
            "jdbc:mysql://localhost:3306/per_press?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    /**
     * 兼容回归：读取存量旧结构 form_def（script 7）重新渲染，
     * 与改造前生成的版本 JMX（script_version id=9）逐字节比对
     */
    @Test
    void legacySingleGroupJmxMustBeByteIdentical() throws Exception {
        String formDefJson;
        String expectedJmx;
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "root", "chaojimima");
             Statement st = conn.createStatement()) {
            formDefJson = queryOne(st, "SELECT form_def FROM script WHERE id=7");
            expectedJmx = queryOne(st, "SELECT jmx_content FROM script_version WHERE id=9");
        }
        if (formDefJson == null || expectedJmx == null) {
            // 库中无该存量数据时跳过（不阻塞其他环境）
            return;
        }
        ScriptFormRequest.FormDef def = objectMapper.readValue(formDefJson, ScriptFormRequest.FormDef.class);
        // script 7 引用 fileId=2（users.csv，CSVDataSet filename 用原始文件名）
        String rebuilt = builder.build(def, Map.of(2L, "users.csv"));
        Assertions.assertEquals(expectedJmx, rebuilt, "旧结构 formDef 渲染 JMX 必须与改造前逐字节一致");
    }

    /**
     * 多单元 CONCURRENT：2 个并行接口 70/30，threads=100 → tg0.threads=70、tg1.threads=30，
     * 每单元独立 ThreadGroup 且采样器只挂本单元的
     */
    @Test
    void multiUnitConcurrentSplitsThreadsByWeights() {
        ScriptFormRequest.FormDef def = parallelTwoSamplersDef();
        TaskCreateRequest.Config config = new TaskCreateRequest.Config();
        config.setThreads(100);
        config.setRampupSeconds(60);
        config.setDurationSeconds(300);
        config.setWeights(List.of(70, 30));
        String jmx = builder.buildWithMode(def, Map.of(), "CONCURRENT", config);
        Assertions.assertTrue(jmx.contains("${__P(tg0.threads,70)}"), "单元0 应分得 70 线程");
        Assertions.assertTrue(jmx.contains("${__P(tg1.threads,30)}"), "单元1 应分得 30 线程");
        Assertions.assertEquals(2, countOccurrences(jmx, "<ThreadGroup "), "应渲染 2 个线程组");
        Assertions.assertTrue(jmx.contains("testname=\"组A-s1-tg0\""), "线程组名应为 单元名-前缀");
        // 采样器归属：s1 只出现在 tg0 子树，s2 只出现在 tg1 子树
        int tg0 = jmx.indexOf("tg0.threads");
        int tg1 = jmx.indexOf("tg1.threads");
        int s1 = jmx.indexOf("testname=\"s1\"");
        int s2 = jmx.indexOf("testname=\"s2\"");
        Assertions.assertTrue(s1 > tg0 && s1 < tg1, "s1 应挂载在 tg0 子树");
        Assertions.assertTrue(s2 > tg1, "s2 应挂载在 tg1 子树");
    }

    /**
     * 多单元 FIXED_TPS：tps=100、70/30 → tg0.tpsPerMin=4200、tg1.tpsPerMin=1800，
     * 每单元 TG 各挂一个 PreciseThroughputTimer
     */
    @Test
    void multiUnitFixedTpsSplitsTpsByWeights() {
        ScriptFormRequest.FormDef def = parallelTwoSamplersDef();
        TaskCreateRequest.Config config = new TaskCreateRequest.Config();
        config.setTps(100);
        config.setDurationSeconds(300);
        config.setWeights(List.of(70, 30));
        String jmx = builder.buildWithMode(def, Map.of(), "FIXED_TPS", config);
        Assertions.assertTrue(jmx.contains("${__P(tg0.tpsPerMin,4200)}"), "单元0 应分得 70 TPS（4200/min）");
        Assertions.assertTrue(jmx.contains("${__P(tg1.tpsPerMin,1800)}"), "单元1 应分得 30 TPS（1800/min）");
        Assertions.assertEquals(2, countOccurrences(jmx, "<PreciseThroughputTimer "), "每单元各挂一个 PTT");
    }

    /**
     * 拆分余数规则：100 按 70/30 → 70/30；101 按 70/30 → 71/30（余数补给占比大的单元）；
     * 均分 100/3 → 34/33/33（Σ=100）
     */
    @Test
    void weightSplitRemainderGoesToLargest() {
        com.per.server.common.WeightSplitter splitter = null; // 静态工具，占位引用
        Assertions.assertNull(splitter);
        int[] r1 = com.per.server.common.WeightSplitter.splitInt(100, new int[]{70, 30});
        Assertions.assertArrayEquals(new int[]{70, 30}, r1);
        int[] r2 = com.per.server.common.WeightSplitter.splitInt(101, new int[]{70, 30});
        Assertions.assertArrayEquals(new int[]{71, 30}, r2);
        int[] r3 = com.per.server.common.WeightSplitter.splitInt(100, new int[]{33, 33, 34});
        Assertions.assertEquals(100, r3[0] + r3[1] + r3[2]);
        int[] r4 = com.per.server.common.WeightSplitter.splitInt(10, new int[]{34, 33, 33});
        Assertions.assertArrayEquals(new int[]{4, 3, 3}, r4);
    }

    /**
     * 构造含一个并行组（2 个 GET 采样器）的表单定义
     *
     * @return 表单场景定义
     */
    private ScriptFormRequest.FormDef parallelTwoSamplersDef() {
        ScriptFormRequest.FormDef def = new ScriptFormRequest.FormDef();
        ScriptFormRequest.Group group = new ScriptFormRequest.Group();
        group.setName("组A");
        group.setExecution("PARALLEL");
        ScriptFormRequest.Sampler s1 = new ScriptFormRequest.Sampler();
        s1.setName("s1");
        s1.setMethod("GET");
        s1.setUrl("http://perpress.example.com/api/a");
        ScriptFormRequest.Sampler s2 = new ScriptFormRequest.Sampler();
        s2.setName("s2");
        s2.setMethod("GET");
        s2.setUrl("http://perpress.example.com/api/b");
        group.setSamplers(List.of(s1, s2));
        def.setGroups(List.of(group));
        return def;
    }

    /**
     * 统计子串出现次数
     *
     * @param text  原文
     * @param token 子串
     * @return 出现次数
     */
    private int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    /**
     * 查询单值
     *
     * @param st  Statement
     * @param sql 查询 SQL
     * @return 第一行第一列值，无结果返回 null
     * @throws Exception 查询失败
     */
    private String queryOne(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
