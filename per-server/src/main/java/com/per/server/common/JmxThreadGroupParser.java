package com.per.server.common;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入 JMX 脚本的线程组（ThreadGroup）解析与属性化改写工具：
 * <ul>
 *   <li>解析：按出现顺序提取普通 ThreadGroup 的 testname 与 num_threads；
 *       setUp/tearDown 线程组标签为 SetupThreadGroup/PostThreadGroup，天然不匹配 {@code <ThreadGroup}，自动排除。</li>
 *   <li>改写：将每个线程组的 num_threads 字面量替换为 ${__P(tgN.threads,原值)}（N 按出现顺序从 0 起），
 *       使服务端可按流量占比经 -JtgN.threads 下发各组线程配额；原值已是 ${...} 表达式时不设默认值（避免逗号破坏 __P 参数）。</li>
 * </ul>
 * 说明：JMeter 保存的 JMX 中 ThreadGroup 元素不会嵌套 ThreadGroup，非贪婪匹配到首个 {@code </ThreadGroup>} 即为该组边界。
 */
public final class JmxThreadGroupParser {

    /** 线程组元素匹配：捕获组 1=testname 属性值，捕获组 2=组内 XML 体 */
    private static final Pattern TG_PATTERN = Pattern.compile(
            "<ThreadGroup\\b[^>]*testname=\"([^\"]*)\"[^>]*>(.*?)</ThreadGroup>", Pattern.DOTALL);

    /** 组内 num_threads 属性匹配：捕获组 1=原始线程数值 */
    private static final Pattern NUM_THREADS_PATTERN = Pattern.compile(
            "<stringProp name=\"ThreadGroup\\.num_threads\">([^<]*)</stringProp>");

    /**
     * 线程组信息。
     *
     * @param name       testname（展示与占比配置用）
     * @param numThreads num_threads 原始字面量（可能为 ${...} 表达式）
     */
    public record ThreadGroupInfo(String name, String numThreads) {
    }

    /** 私有构造器：工具类禁止实例化 */
    private JmxThreadGroupParser() {
    }

    /**
     * 按出现顺序解析 JMX 中的普通线程组列表（空/无线程组返回空列表）。
     *
     * @param jmxContent JMX 脚本文本
     * @return 线程组信息列表
     */
    public static List<ThreadGroupInfo> parse(String jmxContent) {
        List<ThreadGroupInfo> result = new ArrayList<>();
        if (!StringUtils.hasText(jmxContent)) {
            return result;
        }
        Matcher matcher = TG_PATTERN.matcher(jmxContent);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            Matcher numMatcher = NUM_THREADS_PATTERN.matcher(matcher.group(2));
            String numThreads = numMatcher.find() ? numMatcher.group(1).trim() : "";
            result.add(new ThreadGroupInfo(name, numThreads));
        }
        return result;
    }

    /**
     * 属性化改写各线程组 num_threads 为 ${__P(tgN.threads,原值)}（N 按出现顺序从 0 起）；
     * 无线程组或组内无 num_threads 属性时对应组保持原样。
     *
     * @param jmxContent JMX 脚本文本
     * @return 改写后的 JMX 文本
     */
    public static String attributeizeThreads(String jmxContent) {
        if (!StringUtils.hasText(jmxContent)) {
            return jmxContent;
        }
        Matcher matcher = TG_PATTERN.matcher(jmxContent);
        StringBuilder sb = new StringBuilder(jmxContent.length() + 128);
        int index = 0;
        while (matcher.find()) {
            String body = matcher.group(2);
            Matcher numMatcher = NUM_THREADS_PATTERN.matcher(body);
            String newBody = body;
            if (numMatcher.find()) {
                String original = numMatcher.group(1).trim();
                // 原值含 ${ 或逗号时不作为 __P 默认值（逗号会破坏 __P 参数解析）
                String replacement = (original.isEmpty() || original.contains("${") || original.contains(","))
                        ? "${__P(tg" + index + ".threads)}"
                        : "${__P(tg" + index + ".threads," + original + ")}";
                newBody = numMatcher.replaceFirst(Matcher.quoteReplacement(
                        "<stringProp name=\"ThreadGroup.num_threads\">" + replacement + "</stringProp>"));
            }
            // 整体替换该线程组（含标签头），用 quoteReplacement 防止 $ 被当作组引用
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    matcher.group(0).replace(body, newBody)));
            index++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
