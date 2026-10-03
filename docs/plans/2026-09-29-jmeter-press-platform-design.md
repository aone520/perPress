# JMeter 分布式压测平台（PerPress）设计文档

> 版本：v2.0　日期：2026-10-03　状态：M1-M4 已交付，进入迭代优化期
>
> v2.0 变更：对齐交付实现（混合编排/一键调试/接口漏斗/压力机资源监控），
> 修订技术选型与报告体系为实际方案，补全全部数据表与迭代记录。

## 1. 项目概述

基于原生 JMeter 引擎的分布式压测平台，目标：

- Web 页面**编写（表单化编排）& 导入** JMeter 压测脚本
- **混合场景编排**：组内串行（业务链路+参数提取传递）与组内并行（每接口独立线程组）自由组合
- **一键调试**：Server 直连逐接口真实请求，展示完整请求/响应/断言/提取明细，支持单接口调试
- 压测机 **Agent 主动注册、一键部署集群环境**（含 JMeter 引擎自动下发）
- 参数文件（CSV/TXT 自定义分隔符）支持**公用（全量共享）/ 拆分（按行分片）**两种分发模式
- 压测结果**完整丰富**：全局/事务/节点三维度全套分位数、错误分析、压力机资源曲线（CPU/内存/网络带宽）
- 支持**并发模式 / 固定 TPS 模式 / 阶梯模式**，多执行单元流量占比 + **串行链路接口级流量漏斗**
- **架构简单但支持高并发**：Server + MySQL + N×Agent 三类进程，无 RMI、无 Kafka、无时序库

### 开源参考

| 项目 | 借鉴点 |
|---|---|
| MeterSphere | JMeter 集成方式、报告维度设计（但其一站式平台+重架构不采用） |
| Mysterious | 产品形态、JMX/CSV/JAR 上传处理流程 |
| nGrinder | Controller-Agent 分层架构 |
| JMeter 原生 RMI 分布式 | **不采用**：双向连通坑多、master 聚合瓶颈；改为各节点独立执行 + 平台聚合 |

## 2. 总体架构

```
浏览器 ──HTTP──> Nginx(静态+反代) ──> 平台 Server(Spring Boot 单实例) ──读写──> MySQL
                                        ↑ 注册/心跳/轮询/回执/指标上报（Agent 单向出站 HTTP）
                                    压测机集群 Agent×N（Agent + JMeter Non-GUI 独立执行）
                                        ──压测流量──> 被测系统
```

核心决策：

1. **控制面与数据面分离**：Agent 所有连接均为 Agent → Server 方向（注册、心跳、轮询任务、上报指标），穿 NAT/防火墙，压测机零入站端口
2. **无 RMI**：每台压测机 Agent 以子进程方式独立启动 JMeter Non-GUI，平台聚合结果；无单点瓶颈，扩容 = 加机器
3. **极简部署**：平台 docker-compose 一键起（含演示被测服务与 3 台 Agent）；Agent 一条 install.sh 命令安装
4. **Nginx 上游动态解析**：resolver + 变量 proxy_pass，server 容器重建换 IP 不致 502

## 3. 技术选型（v2.0 对齐实现）

| 层 | 选型 |
|---|---|
| Server | Java 17 + Spring Boot 3.2 + MyBatis-Plus + MySQL 8 + jjwt + BCrypt |
| Agent | Java 17 + 精简 Spring Boot（非 Web 模式）+ java.net.http.HttpClient + OSHI（资源采样） |
| 压测引擎 | JMeter 5.6.3 由平台统一下发；吞吐控制用 **Constant Throughput Timer（calcMode=0 每线程限速）**，漏斗用 **ThroughputController（percentThroughput）** |
| 指标 | JTL 逐行解析 + **MetricBuckets 桶合并分位数**（P50/P75/P90/P95/P99/P999），10s 窗口快照 |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + vue-router + axios + ECharts（chartTheme 统一主题 + 设计令牌体系） |
| 实时监控 | 前端 **3s 轮询**（未用 SSE，架构更简单） |
| 部署 | docker-compose（mysql+server+nginx+demo-target+agent×3）；Agent install.sh |

与 v1.0 的差异：HdrHistogram 直方图 blob → JTL+桶聚合；Precise Throughput Timer → CTT（每线程限速，多段/多单元互不干扰）；SSE → 轮询；monaco-editor 暂不引入（表单编排覆盖，JMX 导入兜底）。

## 4. 功能模块（6 个，全部交付）

| 模块 | 能力 | 状态 |
|---|---|---|
| 认证与用户 | 账号密码登录（JWT）、ADMIN/USER 角色、审计日志、强制改密 | ✅ |
| 脚本中心 | 表单化混合编排（串行/并行组、全局环境+UDV、HTTP/断言/请求头/请求体）、三类参数提取器（JSON/正则/边界）+ 测试面板、CSV/TXT 参数文件引用、**一键调试（全量/单接口）**、导入 .jmx、版本留档、复制 | ✅ |
| 节点中心 | 安装命令生成、注册 token、心跳探活、CPU/内存/JVM/**网络带宽**实时监控、标签分组、引擎自动部署与版本一致性 | ✅ |
| 任务中心 | 选脚本+选节点+选模式、**执行单元流量占比（weights）+ 接口漏斗（funnelPercents 任务级覆盖）**、立即/定时执行、克隆、整页编辑、运行中停止 | ✅ |
| 实时监控 | 3s 轮询曲线（TPS/RT/错误率/活跃线程）、任务切换、**运行中停止/完成看报告操作** | ✅ |
| 报告中心 | KPI/分位/趋势/事务/节点/**压力机资源（CPU/内存/网络）**/错误分析/错误样本、HTML 离线导出、打印 | ✅ |

## 5. 核心机制设计

### 5.1 Agent 注册与心跳协议

- 平台生成**注册 token**（存 sys_config，可重置）；Agent 启动需配置 `server.url + register.token`
- 首次注册：`POST /agent/register` → 分配 `nodeKey`（UUID）持久化到 Agent 本地，之后重启用 nodeKey 恢复会话
- 心跳：默认 10s 一次，携带资源快照（CPU%、内存、JVM 内存、**网络收发速率**、引擎版本）
- 轮询：`GET /agent/poll`，START/STOP 指令随轮询下发；回执 `POST /agent/task/receipt` 推进状态机
- **安全兜底**：Agent 失联超阈值自动停止本地压测，防 Server 宕机后压力失控

### 5.2 引擎自动部署

- 平台维护引擎发行版（JMeter + 版本号 + MD5，本机部署时由 agent-data/engine 打包引导）
- Agent 注册/心跳时比对版本，缺旧则下载 `GET /agent/engine/download`，校验 MD5 后解压
- 全节点引擎版本严格一致，规避分布式版本坑

### 5.3 任务生命周期（状态机）

```
CREATED → 校验(脚本/节点在线/文件) → 快照(脚本版本按压测模式重渲染 JMX + 文件分片 + -J 参数包)
→ 节点准备(增量下载→MD5校验→READY回执) → RUNNING(START→10s快照上报)
→ STOPPING(时长到/手动/异常) → FINISHED(聚合报告) / FAILED
```

异常策略：节点超时未 READY → 整体失败；运行中掉线 → 报告标注；Server 重启 → 未完成任务标记 FAILED，Agent 侧失联自停；JMeter 以子进程运行，Agent 重启时清理孤儿进程。

### 5.4 混合编排编译（平台表单 → JMX）

**底座**：JMX 参数化（`${__P(tg0.threads)}` 等，按单元前缀）+ Agent 启动时 `-J` 注入。

**混合分组模型**：
- `groups[]`：每组 `execution = SERIAL | PARALLEL`
- **执行单元** = 流量分配最小粒度：串行组整体 1 个单元；并行组内每个接口各 1 个单元
- 多单元渲染为多个 ThreadGroup（天然并行），压力按 `weights` 拆分（余数补给占比大的单元）

| 模式 | 表单配置 | 编译产物 | 多机拆分 |
|---|---|---|---|
| 并发模式 | 总并发、ramp-up、持续时长 | 每单元 ThreadGroup 参数化 + Duration 调度 | 线程数÷节点数按权重 |
| 固定 TPS | 目标总 TPS、时长、线程上限 | 每单元 ThreadGroup + **CTT（calcMode=0 每线程独立限速）** | TPS÷节点数，各节点独立闭环控速 |
| 阶梯模式 | 起始/步长/每步/峰值/封顶 | 段表展开 N 段×单元 ThreadGroup（TPS 段配 CTT） | 段值÷节点数 |

**串行链路关联**：
- 参数提取器（JSON JSONPath / 正则 / 边界）从响应提取值存入 JMeter 变量，串行组内后续接口 `${refName}` 引用
- 全局配置（协议/域名/端口 + 自定义变量 UDV）：采样器相对路径自动补全为绝对地址；UDV 注入 TestPlan

**接口流量漏斗（仅 FIXED_TPS）**：
- 脚本接口 `trafficPercent`（默认值）+ 任务 `config.funnelPercents`（组名/接口名 → 占比，覆盖默认）
- 渲染为 ThroughputController 百分比模式（属性名 `percentThroughput`，perThread=false 全局统计）包裹采样器
- 形成业务漏斗（如登录100→查价100→下单60→支付30），单元 TPS × 占比 = 接口实际 TPS；各接口独立判定

### 5.5 参数文件分发（公用/拆分）

- **公用模式（SHARED）**：全量文件下发每台压测机
- **拆分模式（SPLIT）**：Server 按实际参测节点数均分行、每分片补表头，跨机参数唯一
- CSV/TXT 均可（按扩展名识别类型；TXT 支持自定义分隔符如 `|`，varNames 固定逗号分隔）
- JMX 零修改：文件放 Agent 工作目录、保持原文件名；分发按 MD5 增量缓存

### 5.6 指标采集与分位数

- Agent 解析 JMeter JTL，按 10s 窗口聚合快照上报（样本数/错误/RT 桶/活跃线程/字节）
- Server 按 (taskId, nodeKey, sampler, windowStart) 幂等入库，MetricBuckets 桶合并计算全局分位数
- 错误样本每窗口限 10 条、全任务累计前 200 条（ts/sampler/responseCode/message 对齐前端字段）

### 5.7 一键调试（不落库、不依赖压测节点）

- `POST /api/scripts/debug`：Server 按表单定义逐接口顺序发起真实请求
- 变量池：全局 UDV + CSV/TXT 首行数据 + 串行链路提取值向后传递
- 返回每接口完整请求（方法/URL/头/体）+ 响应（状态码/头/体/耗时）+ 断言结果 + 提取结果
- 残留 `${xxx}` 提前拦截并给出可用变量清单；支持构造单接口 formDef 单独调试
- 前端抽屉展示：摘要条 + 每接口折叠卡片，自动展开首个失败接口

### 5.8 压力机资源监控（轻量，复用心跳通道）

- Agent 心跳本就携带 CPU/内存；网络为 OSHI NetworkIF 累计字节**差分**速率（排除 lo/容器虚拟网卡，多网卡求和）
- 任务运行期间 Server 将心跳资源快照落 `node_resource_sample`（10s 粒度），不触碰压测指标链路，Agent 采样开销微秒级
- 报告输出：每节点 resources 时序（与指标序列同秒级时间轴）+ cpuPeak/memPeak/netPeakBps
- 前端三图：CPU% / 内存% / 网络带宽（收+发合计，tooltip 看分解）；节点明细表峰值列
- 用途：TPS 上不去时同屏判断压力机瓶颈（CPU 打满 / 带宽限速）还是被测系统瓶颈

## 6. 数据库设计（MySQL 8，utf8mb4，全部已交付）

| 表 | 用途 |
|---|---|
| `user` | 用户（BCrypt 密码/角色/状态） |
| `node` | 压测节点（node_key/心跳/资源含 net_recv_bps/net_sent_bps/标签/引擎版本） |
| `audit_log` | 审计日志 |
| `sys_config` | 系统配置（注册 token 等） |
| `script` / `script_version` | 脚本主表（含 formDef JSON）与版本（JMX 留档） |
| `data_file` | 文件库（CSV/TXT/JAR/BIN，md5 去重存储） |
| `test_task` / `task_node` | 任务与节点关联（状态/分片/-J 参数） |
| `task_script_snapshot` | 任务脚本快照（按模式重渲染的 JMX） |
| `metric_snapshot` | 10s 指标快照（按 taskId/nodeKey/sampler/windowStart 幂等） |
| `error_sample` | 错误样本明细（前 200 条） |
| `test_report` | 任务结束固化聚合报告（5 个 JSON 分区） |
| `node_resource_sample` | 任务期间压力机资源采样（CPU/内存/网络收发） |

## 7. API 设计（对齐实现）

**管理面 `/api/**`（JWT）：**
- 认证：`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/change-password`
- 节点：`GET /api/nodes`、标签/删除/注册 token 重置/安装命令
- 脚本：CRUD、`POST /api/scripts/form`（表单创建）、`POST /api/scripts/import`、版本管理、复制、**`POST /api/scripts/debug`（一键调试）**
- 文件：`POST /api/files`（上传）、分页
- 任务：CRUD、`POST /api/tasks/{id}/start|stop|copy`、`GET /api/tasks/{id}/metrics|report`、`GET /api/tasks/{id}/report/export`
- 用户/审计/引擎包管理

**Agent 面 `/agent/**`（token）：**
- `POST /agent/register`、`POST /agent/heartbeat`（含网络速率）、`GET /agent/poll`、`POST /agent/task/receipt`、`POST /agent/metrics`（快照+错误样本）、`GET /agent/engine/download`、任务文件下载

## 8. 报告体系（v2.0 按实际交付修订）

1. **报告头**：任务/脚本/模式/节点数/起止时间/状态
2. **全局 KPI 八卡**：总请求/总错误/错误率/平均 TPS/峰值 TPS/吞吐 KB/s/峰值线程/APDEX
3. **RT 分位单行表**：Min/Max/Avg/P50/P75/P90/P95/P99/P999
4. **趋势曲线**：TPS（叠加错误数）、RT 多分位、错误时间分布
5. **事务明细**：每事务全指标（含补算错误率，可排序）
6. **节点明细**：每节点 TPS/错误/分位/流量 + **CPU/MEM/网络峰值** + 流量占比树（串行组内嵌接口漏斗子行）
7. **压力机资源三图**：CPU% / 内存% / 网络带宽（收+发合计）
8. **错误分析**：错误码分布、TOP 错误事务（含错误率）、错误时间分布、错误样本明细（真实 ts）

导出：HTML 离线完整报告；打印支持（另存 PDF）。监控大屏复用同一指标体系（3s 轮询）。

## 9. 部署方案（已交付）

- **平台一键体验**：`bash deploy/deploy-local.sh` = 编译双 jar → 构建镜像 → 打包引擎 zip → 拷贝前端 dist → 启动 `docker-compose.local.yml`（mysql + server + nginx + **demo-target 演示被测服务** + **agent×3 压力机**）
- **演示数据灌入**：`bash deploy/seed-demo.sh` = 登录 → 上传 users.csv（逗号）/browse.txt（`|` 分隔）→ 创建「混合压测场景」示例脚本（串行下单链路 4 接口逐级提取 + 并行浏览 3 接口）→ 自动一键调试验证；幂等可重跑
- **端口**：Nginx=80、Server=8180、被测=9090（与开发环境 8080/3306/5173 隔离）
- **前端产物更新**：清空 dist 内容保留目录（bind mount inode 坑）；Nginx 上游动态解析防 502
- **Agent**：install.sh = 拉包 → 解压 → 写 server.url+token → 拉起 → 自动注册
- 初始化：admin/admin123；`schema.sql` 自动执行

## 10. 里程碑与迭代记录

| 阶段 | 范围 | 状态 |
|---|---|---|
| M1 基座 | 用户/JWT、节点注册/心跳/标签、Agent 骨架、前端布局/节点管理 | ✅ 交付 |
| M2 脚本与执行 | JMX 导入+文件库+版本、并发模式全流程、-J 参数化、文件公用/拆分 | ✅ 交付 |
| M3 模式与统计 | TPS/阶梯模式、10s 快照、分位数、监控大屏 | ✅ 交付 |
| M4 报告与打磨 | 报告全区块、错误分析、HTML 导出、定时任务、审计 | ✅ 交付 |

**v2.0 迭代增量**：
- 表单脚本重构：混合分组编排（串行/并行）、全局环境+UDV、三类提取器+测试面板
- 一键调试（全量/单接口）、CSV/TXT 参数文件变量支持
- 接口流量漏斗：脚本默认值 + 任务级覆盖（FIXED_TPS ThroughputController）
- 执行单元 weights 流量占比（创建/详情树形展示，串行组内嵌接口子行）
- 压力机资源监控：CPU/内存/网络带宽（心跳通道，报告三图）
- 监控大屏操作按钮（停止任务/查看报告）、错误样本明细修复、事务明细错误率补算
- 全局 UI 改版（设计令牌 + chartTheme）、侧栏折叠、任务整页编辑
- 演示环境：docker 一键部署 + seed 混合压测示例（含 /api/mix/* mock 服务）

## 11. 工程结构（实际）

```
per/
├── README.md                   # 项目说明
├── docs/plans/                 # 设计文档
├── scripts/                    # demo-target-server.js 演示被测服务（含 /api/mix/* 混合场景接口）
├── per-server/                 # Spring Boot 服务端 :8080
├── per-agent/                  # Agent（非 Web 模式，OSHI 资源采样）
├── per-web/                    # Vue3 前端（设计令牌 + chartTheme）
├── deploy/
│   ├── deploy-local.sh         # 一键 Docker 部署（平台+3 Agent+被测服务）
│   ├── docker-compose.local.yml
│   ├── seed-demo.sh            # 演示数据灌入（混合压测示例脚本）
│   ├── demo-data/              # users.csv / browse.txt 参数文件
│   ├── nginx.conf              # 动态解析上游
│   └── Dockerfile.server / Dockerfile.agent
└── agent-install/install.sh    # Agent 一键安装
```
