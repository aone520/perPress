package com.per.agent.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.per.agent.common.Jsons;
import com.per.agent.config.AgentProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 服务端 HTTP 客户端：封装 /agent/register、/agent/heartbeat、/agent/poll、/agent/task/receipt、
 * /agent/metrics 五个接口调用。
 * <p>基于 java.net.http.HttpClient 全局单例（连接超时 5s，请求超时 10s），
 * 统一解包响应体 {"code":0,"message":"ok","data":{...}}，code != 0 时抛 AgentServerException。</p>
 */
@Component
public class ServerClient {

    /** 单次请求超时时间 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 连接超时时间 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final AgentProperties properties;

    /** 全局单例 HTTP 客户端 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 构造服务端客户端。
     *
     * @param properties Agent 配置
     */
    public ServerClient(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 调用注册接口：提交本机身份信息，返回 node_key 与引擎发布信息。
     *
     * @param request 注册请求体
     * @return 注册响应（含 nodeKey 与 engine）
     */
    public RegisterResponse register(RegisterRequest request) {
        return post("/agent/register", request, RegisterResponse.class);
    }

    /**
     * 调用心跳接口：上报本机资源快照。
     *
     * @param request 心跳请求体
     * @return 心跳响应（含 serverTime）
     */
    public HeartbeatResponse heartbeat(HeartbeatRequest request) {
        return post("/agent/heartbeat", request, HeartbeatResponse.class);
    }

    /**
     * 调用轮询接口：拉取当前节点待执行指令（command: PREPARE/START/STOP，无指令时 command 为空）。
     *
     * @param nodeKey 节点密钥
     * @return 轮询响应（command/task，无指令时 data 或 command 为空）
     */
    public PollResponse poll(String nodeKey) {
        String encodedKey = URLEncoder.encode(nodeKey, StandardCharsets.UTF_8);
        return get("/agent/poll?nodeKey=" + encodedKey, PollResponse.class);
    }

    /**
     * 发送任务回执：POST /agent/task/receipt，body {taskId,nodeKey,phase,message}，
     * phase 取值 READY/RUNNING/FINISHED/STOPPED/FAILED。
     *
     * @param receipt 回执请求体
     */
    public void sendReceipt(TaskReceipt receipt) {
        post("/agent/task/receipt", receipt, JsonNode.class);
    }

    /**
     * 发送压测指标上报（M3 每窗一报）：POST /agent/metrics，body {taskId,nodeKey,windowStart,windowEnd,
     * finished,samplers:[...],errors:[...]}；统一解包 {"code","message","data"}，
     * code!=0 抛 AgentServerException（由调用方决定告警/忽略，本接口不重试）。
     *
     * @param report 窗口指标报告
     */
    public void sendMetrics(MetricsReport report) {
        post("/agent/metrics", report, JsonNode.class);
    }

    /**
     * 发送 POST 请求并解包统一响应。
     *
     * @param path 接口路径
     * @param body 请求体对象（序列化为 JSON）
     * @param type 响应 data 目标类型
     * @param <T>  data 目标类型
     * @return data 结构反序列化结果
     */
    private <T> T post(String path, Object body, Class<T> type) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getServerUrl() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Jsons.write(body), StandardCharsets.UTF_8))
                .build();
        return exchange(request, type);
    }

    /**
     * 发送 GET 请求并解包统一响应。
     *
     * @param path 接口路径（含查询参数）
     * @param type 响应 data 目标类型
     * @param <T>  data 目标类型
     * @return data 结构反序列化结果
     */
    private <T> T get(String path, Class<T> type) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getServerUrl() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return exchange(request, type);
    }

    /**
     * 执行 HTTP 请求并按统一响应结构解包：HTTP 非 200、网络异常、code != 0 均抛 AgentServerException。
     *
     * @param request 已构建的 HTTP 请求
     * @param type    data 目标类型
     * @param <T>     data 目标类型
     * @return data 结构反序列化结果（data 为 null 时返回 null）
     */
    private <T> T exchange(HttpRequest request, Class<T> type) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentServerException(-1, "请求被中断: " + request.uri());
        } catch (IOException e) {
            throw new AgentServerException(-1, "网络请求失败: " + e.getMessage());
        }
        String body = response.body();
        if (response.statusCode() != 200) {
            throw new AgentServerException(response.statusCode(), "HTTP 状态异常(" + response.statusCode() + "): " + body);
        }
        ApiEnvelope<JsonNode> envelope;
        try {
            envelope = Jsons.MAPPER.readValue(body, new TypeReference<ApiEnvelope<JsonNode>>() {
            });
        } catch (JsonProcessingException e) {
            throw new AgentServerException(-1, "响应 JSON 解析失败: " + body);
        }
        if (envelope.code() != 0) {
            throw new AgentServerException(envelope.code(), envelope.message());
        }
        JsonNode data = envelope.data();
        if (data == null || data.isNull()) {
            return null;
        }
        return Jsons.MAPPER.convertValue(data, type);
    }

    /**
     * 服务端统一响应信封结构 {"code":0,"message":"ok","data":{...}}。
     */
    private record ApiEnvelope<T>(int code, String message, T data) {
    }
}
