# JMeter 分布式压测平台（PerPress）设计文档

> 版本：v1.0　日期：2026-09-29　状态：已评审，进入 M1 开发

## 1. 项目概述

基于原生 JMeter 引擎的分布式压测平台，目标：

- Web 页面**编写（表单化编排）& 导入** JMeter 压测脚本
- 压测机**Agent 主动注册、一键部署集群环境**（含 JMeter 引擎自动下发）
- 参数文件（CSV 等）支持**公用（全量共享）/ 拆分（按行分片）**两种分发模式
- 压测结果**完整丰富**：全局/事务/节点三维度全套分位数（P50/P75/P90/P95/P99/P999）、RT 分布、长尾、错误分析、历史对比
- 支持**并发模式 / 固定 TPS 模式 / 阶梯模式**，可配置压测时长、启动时长（ramp-up）
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
浏览器 ──HTTP──> 平台 Server(Spring Boot 单实例) ──读写──> MySQL
                    ↑ 注册/心跳/拉任务/上报指标（Agent 单向出站 HTTP）
                压测机集群 Agent×N（Agent + JMeter Non-GUI 独立执行）
                    ──压测流量──> 被测系统
```

核心决策：

1. **控制面与数据面分离**：Agent 所有连接均为 Agent → Server 方向（注册、心跳、轮询任务、上报指标），穿 NAT/防火墙，压测机零入站端口
2. **无 RMI**：每台压测机 Agent 以子进程方式独立启动 JMeter Non-GUI，平台聚合结果；无单点瓶颈，扩容 = 加机器
3. **极简部署**：平台 docker-compose 一键起；Agent 一条 install.sh 命令安装

## 3. 技术选型

| 层 | 选型 |
|---|---|
| Server | Java 17 + Spring Boot 3.2 + MyBatis-Plus + MySQL 8 + jjwt + BCrypt |
| Agent | Java 17 + 精简 Spring Boot（非 Web 模式）+ java.net.http.HttpClient |
| 压测引擎 | JMeter 5.6.x + jmeter-plugins（ConcurrencyThreadGroup、SteppingThreadGroup、Throughput Shaping Timer、HdrHistogram Backend）由平台统一下发 |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + vue-router + axios + ECharts + monaco-editor（JMX 源码模式） |
| 部署 | docker-compose（server+mysql+nginx）；Agent install.sh |

## 4. 功能模块（6 个）

| 模块 | 能力 |
|---|---|
| 认证与用户 | 账号密码登录（JWT）、ADMIN/USER 角色、审计日志 |
| 脚本中心 | 表单化编排（线程组/HTTP 请求/断言/头/CSV 数据源/定时器）+ 导入 .jmx + 文件库（CSV/JAR/二进制）+ 版本留档回滚 |
| 节点中心 | 安装命令生成、注册 token、心跳探活、CPU/内存/JVM 监控、标签分组、引擎自动部署与版本一致性 |
| 任务中心 | 选脚本+选节点（标签批量）+选模式+时长/ramp-up、立即/定时执行、克隆、运行中停止 |
| 实时监控 | 10s 粒度实时曲线（TPS/RT/错误率/活跃线程）、节点状态、jmeter.log tail |
| 报告中心 | 8 区块全量报告（见 §8）、HTML 离线导出 |

## 5. 核心机制设计

### 5.1 Agent 注册与心跳协议

- 平台生成**注册 token**（存 sys_config，可重置）；Agent 启动需配置 `server.url + register.token`
- 首次注册：`POST /agent/register` → 分配 `nodeId`（UUID）持久化到 Agent 本地，之后重启用 nodeId 直连
- 心跳：默认 10s 一次，携带资源快照（CPU%、内存、JVM 内存、引擎版本）；超过 30s 未心跳判定 OFFLINE
- 轮询：默认 2s 一次 `GET /agent/poll`，START/STOP 指令随轮询下发（控制延迟秒级，无需长连接）
- **安全兜底**：Agent 失联超阈值（默认 60s 可配）自动停止本地压测，防 Server 宕机后压力失控

### 5.2 引擎自动部署

- 平台维护引擎发行版（官方 JMeter + 预置插件打包 zip + 版本号 + MD5）
- Agent 注册/心跳时比对版本，缺旧则从 Server 下载 `GET /agent/engine/download`，校验 MD5 后解压到本地 engine 目录
- 全节点引擎版本严格一致，规避分布式版本坑

### 5.3 任务生命周期（状态机）

```
CREATED → 校验(脚本/节点在线/文件) → DISPATCHING(冻结快照: 脚本版本+文件分片+-J参数包)
→ 节点准备(增量下载→MD5校验→READY回执) → RUNNING(START→10s快照上报)
→ STOPPING(时长到/手动/异常) → FINISHED(聚合报告) / FAILED / PARTIAL(部分节点失败)
```

异常策略：节点超时未 READY → 剔除或整体失败（可配）；运行中掉线 → 其余继续、报告标注；Server 重启 → 未完成任务标记 FAILED，Agent 侧失联自停；JMeter 以子进程运行，Agent 重启时清理孤儿进程。

### 5.4 压测模式编译（平台表单 → JMX）

**底座**：JMX 参数化（`${__P(threads)}` 等）+ Agent 启动时 `-J` 注入，配置与脚本解耦；导入的 JMX 平台可选自动参数化改写。

| 模式 | 表单配置 | 编译产物 | 多机拆分 |
|---|---|---|---|
| 并发模式 | 总并发、ramp-up、持续时长/循环次数 | ThreadGroup 参数化 + Duration 调度 | 线程数÷节点数，余数补前几台 |
| 固定 TPS | 目标总 TPS、时长、线程上限(可选) | ConcurrencyThreadGroup + Precise Throughput Timer | TPS÷节点数，各节点独立闭环控速 |
| 阶梯模式 | 起始压力、步长、每步持续、峰值、封顶时长 | 并发阶梯→SteppingThreadGroup；TPS 阶梯→Throughput Shaping Timer schedule 段表 | 峰值/步长÷节点数；配置页时间线预览 |

### 5.5 参数文件分发（公用/拆分）

- **公用模式**：全量文件下发每台压测机（数据可重复场景）
- **拆分模式**：Server 按实际参测节点数 N 均分行、每分片补表头，Agent 各取己片（跨机参数唯一）；下发时固化分片快照
- JMX 零修改：文件放 Agent 工作目录、保持原文件名，相对路径天然生效
- 分发协议：文件清单（URL+MD5），Agent 按 MD5 增量缓存；JAR/二进制始终全量

### 5.6 指标采集与精确分位数

- Agent 本地 **HdrHistogram** 记录响应时间，每 10s 上报**可合并直方图 blob**（非逐请求）
- Server 合并直方图计算全局分位数，P50/P75/P90/P95/P99/P999 跨机数学精确
- 错误明细采样上报前 200 条（响应码、断言信息、截断响应体）

## 6. 数据库设计（MySQL 8，utf8mb4）

**M1 表：**

```sql
CREATE TABLE `user` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `username` VARCHAR(64) NOT NULL UNIQUE,
  `password` VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  `nickname` VARCHAR(64) DEFAULT NULL,
  `role` VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'ADMIN/USER',
  `status` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '用户';

CREATE TABLE `node` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `node_key` VARCHAR(64) NOT NULL UNIQUE COMMENT '节点UUID',
  `hostname` VARCHAR(128), `ip` VARCHAR(64), `os` VARCHAR(64),
  `jvm_version` VARCHAR(64), `agent_version` VARCHAR(32),
  `engine_version` VARCHAR(32) COMMENT '已部署JMeter引擎版本',
  `labels` VARCHAR(512) COMMENT '标签,逗号分隔',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE',
  `cpu_usage` DOUBLE, `mem_usage` DOUBLE, `mem_total` BIGINT,
  `jvm_mem_used` BIGINT, `jvm_mem_max` BIGINT,
  `last_heartbeat_time` DATETIME,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '压测节点';

CREATE TABLE `audit_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id` BIGINT, `username` VARCHAR(64),
  `action` VARCHAR(128), `detail` VARCHAR(1024), `ip` VARCHAR(64),
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '审计日志';

CREATE TABLE `sys_config` (
  `config_key` VARCHAR(64) PRIMARY KEY,
  `config_value` VARCHAR(1024),
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '系统配置(注册token等)';
```

**M2/M3 表（后续创建）：** `script`、`script_version`、`data_file`、`test_task`、`task_node`、`task_snapshot`、`metric_snapshot`（10s 快照+直方图 blob）、`test_report`（汇总/事务/节点/错误维度 JSON 固化）。

## 7. API 设计

**管理面 `/api/**`（JWT 认证）：**

- `POST /api/auth/login`、`GET /api/auth/me`
- `GET /api/nodes`、`PUT /api/nodes/{id}/labels`、`DELETE /api/nodes/{id}`、`POST /api/nodes/register-token/reset`、`GET /api/nodes/install-command`
- `GET|POST|PUT /api/users`（ADMIN）
- `GET /api/audit-logs`
- M2+：脚本 CRUD、文件上传、任务创建/停止/详情、报告查询、`GET /api/task/{id}/stream`（SSE）

**Agent 面 `/agent/**`（token 认证）：**

- `POST /agent/register` {token,hostname,ip,os,jvmVersion,agentVersion} → {nodeKey,engine:{version,md5,url}}
- `POST /agent/heartbeat` {nodeKey,cpu,mem...,engineVersion} → {serverTime}（预留控制指令）
- `GET /agent/poll?nodeKey=` → {command:START/STOP/REFRESH_ENGINE, task|null}（M1 返回 null）
- `GET /agent/engine/download`（M1 预留，404 直到上传引擎包）

## 8. 报告体系（8 区块全量指标）

1. **报告头**：任务/脚本/模式/节点数/起止时间/时长/状态
2. **全局汇总 KPI**：总请求、错误数/率、平均/峰值 TPS、吞吐量 KB/s、收/发字节、活跃线程峰值与均值、节点 CPU/内存峰值
3. **响应时间全套分位数**：Min/Max/Avg/StdDev/P50/P75/P90/P95/P99/P999 + RT 落桶直方图 + 累积分布
4. **趋势曲线组（10s，共 6 组同轴联动）**：TPS（叠加错误数+阶梯计划对比）、RT 多线（Min/Avg/Max/P50/P90/P95/P99 可勾选）、错误率、活跃线程、收/发带宽、节点 CPU/内存
5. **事务维度明细**：每事务全指标（请求数/错误率/TPS/全套分位数/字节/平均大小，可排序）
6. **节点维度明细**：每节点 TPS/错误率/Avg/P95/P99/资源/起止状态/负载均衡度
7. **错误分析**：类型分布（4xx/5xx/超时/连接失败/断言失败）、TOP 错误事务、错误时间分布、样本明细
8. **RT 分布与长尾 + 历史对比**：长尾占比（>1s/>3s）、慢事务排行、同脚本多任务回归对比

导出：HTML 离线完整报告（自包含图表）。实时监控大屏复用同一指标体系（SSE 10s 刷新）。

对标：JMeter 官方 HTML Report 全包含（Statistics/Errors/Over-Time 曲线/RT 分布），平台增强（计划对比、节点维度、资源曲线、历史回归）。

## 9. 部署方案

- **平台**：`docker-compose.yml` = per-server + mysql8 + nginx(per-web 静态)；也支持 jar 直跑
- **Agent**：install.sh = curl 拉 Agent 发行包 → 解压 → 写入 server.url+token → systemd/nohup 拉起 → 自动注册
- 初始化：默认账号 admin/admin123（首次强制改密，M4）；`schema.sql` 自动执行

## 10. 里程碑

| 阶段 | 范围 |
|---|---|
| **M1 基座** | 用户登录/JWT、节点注册/心跳/资源监控/标签、Agent 骨架（注册/心跳/轮询/资源采集/引擎部署框架）、前端登录/布局/节点管理页 |
| M2 脚本与执行 | JMX 导入+文件库+脚本版本、并发模式任务全流程（单机→多机）、-J 参数化注入、文件公用/拆分 |
| M3 模式与统计 | TPS/阶梯模式、10s 快照上报、HdrHistogram 精确分位数、实时监控大屏 |
| M4 报告与打磨 | 8 区块完整报告、错误分析、HTML 导出、定时任务、审计、快照过期清理 |

## 11. 工程结构

```
per/
├── docs/plans/                 # 设计文档
├── per-server/                 # Spring Boot 服务端 :8080
├── per-agent/                  # Agent（非 Web 模式）
├── per-web/                    # Vue3 前端
├── docker-compose.yml          # 平台一键部署（M4）
└── agent-install/install.sh    # Agent 一键安装（M2）
```
