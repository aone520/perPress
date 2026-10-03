# PerPress — 基于 JMeter 的分布式压测平台

基于原生 JMeter 引擎的轻量级分布式压测平台：表单化编排混合场景（串行链路 + 并行接口）、一键调试、三种压测模式、精确分位数报告、压力机资源监控。架构极简（Server + MySQL + N×Agent），无 RMI、无 Kafka、无时序库，Agent 单向出站穿 NAT，扩容 = 加机器。

```
浏览器 ──> Nginx(静态+反代) ──> Server(Spring Boot) ──> MySQL
                                  ↑ 注册/心跳/轮询/指标上报（单向出站）
                              Agent×N（JMeter Non-GUI 独立执行）──压测流量──> 被测系统
```

## 核心特性

| 特性 | 说明 |
|---|---|
| **混合场景编排** | 组内串行（业务链路）与组内并行（每接口独立线程组）自由组合；全局环境（协议/域名/端口 + 自定义变量），接口写相对路径即可 |
| **参数提取传递** | JSON / 正则 / 边界三类提取器，串行链路上游响应值自动作为下游入参（如登录 token → 下单头），附提取器测试面板 |
| **一键调试** | Server 直连逐接口真实请求，展示完整请求/响应/断言/提取明细；支持单接口调试；CSV/TXT 参数文件变量参与调试 |
| **三种压测模式** | 并发模式 / 固定 TPS（CTT 每线程限速，不超发）/ 阶梯模式；多执行单元流量占比（weights） |
| **接口流量漏斗** | 固定 TPS 下串行链路按接口百分比放行（登录100→下单60→支付30），脚本配默认值、任务可覆盖，报告同屏对照 |
| **参数文件分发** | CSV/TXT（自定义分隔符）支持公用（全量共享）/ 拆分（按行分片，跨机参数唯一），MD5 增量下发 |
| **精确分位数** | JTL 3s 窗口聚合（`metrics-window-ms` 可配）+ 桶合并，P50/P75/P90/P95/P99/P999 跨节点数学精确 |
| **压力机资源监控** | 复用心跳通道（10s 粒度，Agent 近零开销）采集 CPU/内存/网络带宽，报告三图同屏定位"压力机瓶颈 vs 服务瓶颈" |
| **完整报告** | KPI/APDEX/RT 分位/TPS 趋势/事务明细/节点明细（含流量占比树）/错误分析/错误样本/压力机资源；HTML 离线导出与打印 |
| **实时监控大屏** | 3s 轮询 TPS/RT/错误率/活跃线程曲线；运行中可停止任务、完成后直达报告 |
| **引擎自动部署** | Agent 注册后自动下载部署 JMeter 引擎，全节点版本严格一致 |

## 快速开始（Docker 一键体验）

前置：Docker（含 docker compose）、JDK 17、Maven、Node 18+（用于构建产物）。

```bash
# 1. 一键部署：平台 + 3 台压力机 Agent + 演示被测服务（7 容器）
bash deploy/deploy-local.sh

# 2. 灌入演示数据：参数文件 + 混合压测示例脚本 + 自动调试验证（幂等可重跑）
bash deploy/seed-demo.sh
```

启动完成后：

| 入口 | 地址 |
|---|---|
| 平台页面 | http://localhost（admin / admin123） |
| API 直连 | http://localhost:8180 |
| 演示被测服务 | http://localhost:9090/api/hello |

3 台 Agent（per-agent-1/2/3）自动注册并部署 JMeter 引擎（首次约 1-2 分钟），节点管理页可见。

## 演示脚本：混合压测场景

seed 脚本自动创建的示例（`混合压测场景-电商下单+浏览流量`）：

- **串行组「下单主链路」**（引用 `users.csv`，逗号分隔）：
  `登录 →[提取token]→ 查价 →[提取price]→ 下单 →[提取orderId]→ 支付`
  下游接口的头/请求体以 `${token}` 等引用上游响应值
- **并行组「浏览流量」**（引用 `browse.txt`，`|` 分隔 TXT）：信息流 / 推荐 / 搜索 三接口各自独立线程组
- mock 服务 `/api/mix/*` 对 token/orderId 强校验——提取链路断裂会立即 401/400 可见

发一个固定 TPS 任务（建议 weights 40/20/20/20，接口漏斗 下单60%/支付30%），即可在报告看到：各接口漏斗比例、三节点资源曲线、错误明细。

## 功能导览

1. **脚本中心**：表单创建（混合编排/全局配置/断言/提取器/参数文件引用/流量占比默认值）或导入 .jmx；版本留档、复制；「调试」按钮全量调试，接口卡片可单接口调试
2. **任务中心**：新建任务（选脚本/版本/模式/节点/文件分发策略）；多单元时配置流量占比（串行组可展开设接口漏斗）；定时/立即执行；运行中停止；克隆
3. **监控大屏**：选择运行中任务，实时曲线 + 停止/查看报告操作
4. **报告中心**：任务结束自动生成；可导出 HTML、打印为 PDF
5. **节点管理**：在线状态、CPU/内存/JVM/网络实时值、标签、引擎版本、安装命令
6. **文件库**：CSV/TXT/JAR/BIN 上传（md5 去重）

## 生产部署

- **平台**：`deploy/` 提供镜像构建（build.sh）与 compose 编排；也可 jar 直跑（per-server :8080）
- **压力机**：目标机器执行节点管理页生成的安装命令（install.sh：拉包 → 配置 server.url+token → 拉起 → 自动注册）
- **压测局域网/被测系统**：压力机与被测系统同网段直连（分布式压测压力机应靠近被测系统）

## 技术栈

| 层 | 技术 |
|---|---|
| per-server | Java 17 · Spring Boot 3.2 · MyBatis-Plus · MySQL 8 · JWT · BCrypt |
| per-agent | Java 17 · Spring Boot（非 Web）· java.net.http.HttpClient · OSHI |
| 引擎 | JMeter 5.6.3（平台统一下发）· ConstantThroughputTimer · ThroughputController |
| per-web | Vue 3 · Vite · Element Plus · Pinia · ECharts（设计令牌 + chartTheme） |

## 目录结构

```
per/
├── docs/plans/                 # 设计文档（v2.0）
├── scripts/demo-target-server.js   # 演示被测服务（含 /api/mix/* 混合场景接口）
├── per-server/                 # 服务端 :8080
│   └── src/main/java/com/per/server
│       ├── controller/         # api/**（JWT）与 agent/**（token）两面
│       ├── service/            # FormScriptJmxBuilder(JMX编译) ScriptDebugService(调试)
│       │                       # TaskService(状态机) ReportService(聚合) 等
│       └── ...
├── per-agent/                  # Agent
│   └── src/main/java/com/per/agent
│       ├── core/               # 生命周期/心跳/任务执行器
│       ├── monitor/            # SystemResourceCollector(CPU/内存/网络差分)
│       └── metrics/            # JTL 解析与窗口聚合
├── per-web/                    # 前端（views: script/task/monitor/report/node/file…）
├── deploy/
│   ├── deploy-local.sh         # 一键 Docker 部署
│   ├── docker-compose.local.yml
│   ├── seed-demo.sh            # 演示数据灌入
│   ├── demo-data/              # users.csv / browse.txt
│   └── nginx.conf              # 上游动态解析
└── agent-install/install.sh    # Agent 一键安装
```

## 本地开发

```bash
# 服务端（默认 8080，依赖本地 MySQL，schema.sql 自动执行）
cd per-server && mvn spring-boot:run

# Agent（指定平台地址与注册 token）
cd per-agent && mvn spring-boot:run -Dspring-boot.run.arguments="--per.agent.server-url=http://localhost:8080 --per.agent.register-token=<token>"

# 前端（开发代理到 8080）
cd per-web && npm i && npm run dev
```

默认账号 `admin / admin123`。注册 token 在节点管理页查看/重置。

## 已知设计取舍

- 指标上报为 3s 窗口（`per.agent.metrics-window-ms` 可配）；心跳（资源快照）为 10s，两通道独立
- 实时监控用 3s 轮询（未用 SSE/WebSocket），换架构简单
- 分位数基于 3s 窗口桶聚合（非逐请求直方图 blob），精度满足压测场景
- 导入的 JMX 仅支持并发模式；表单脚本支持全部三模式
- 接口漏斗各接口比例独立判定（某迭代下游放行上游跳过时用旧值/默认值），详见设计文档 §5.4

更多信息见 [设计文档](docs/plans/2026-09-29-jmeter-press-platform-design.md)。
