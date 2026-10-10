#!/usr/bin/env bash
# =====================================================================
# 构建 PerPress Agent 一键安装分发包（per-agent-dist.tar.gz）
#
# 分发包结构（解压到 /opt/perpress-agent）：
#   bin/perpress-agent        Agent 启动脚本（cd 安装目录 → java -jar lib/*.jar，写 PID 文件）
#   lib/per-agent-*.jar       Spring Boot 可执行 jar（mvn package 产物）
#   config/application.yml    配置模板（占位符 __SERVER_URL__/__REGISTER_TOKEN__，
#                             由平台 install.sh 用 sed 渲染，其余默认值同 per-agent 工程）
#   systemd/perpress-agent.service  systemd 单元文件示例
#
# 产物输出路径：per-agent/target/per-agent-dist.tar.gz
# 使用：将产物复制到 per-server 的 {per.storage.dir}/agent-dist/ 目录下
#       （默认 ./server-data/agent-dist/per-agent-dist.tar.gz），
#       之后节点执行「一键安装命令」即可自动下载安装
# =====================================================================
set -euo pipefail

# 脚本所在目录的上一级即项目根目录（脚本位于 {root}/scripts/）
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_DIR="${ROOT}/per-agent"
DIST_ROOT="${AGENT_DIR}/target/dist"
DIST_DIR="${DIST_ROOT}/perpress-agent"
OUT_TGZ="${AGENT_DIR}/target/per-agent-dist.tar.gz"

# 统一切到 agent 工程目录（后续 jar 定位/组装均按相对路径 target/ 执行）
cd "${AGENT_DIR}"

# ---------------------------------------------------------------------
# [1/4] 编译打包 per-agent（跳过测试；PER_AGENT_SKIP_BUILD=1 时复用已有 jar，
#       供 Docker 多阶段构建复用第一次 mvn 产物，避免重复编译）
# ---------------------------------------------------------------------
if [ "${PER_AGENT_SKIP_BUILD:-0}" = "1" ] \
    && ls target/per-agent-*.jar >/dev/null 2>&1 \
    && [ -n "$(ls target/per-agent-*.jar 2>/dev/null | grep -v 'sources' | head -n 1)" ]; then
  echo "[1/4] 复用已有 per-agent jar（PER_AGENT_SKIP_BUILD=1）"
else
  echo "[1/4] 编译打包 per-agent ..."
  mvn -q clean package -DskipTests
fi

# 定位 Spring Boot 可执行 jar（排除 sources 等附属产物）
JAR_FILE="$(ls target/per-agent-*.jar 2>/dev/null | grep -v 'sources' | head -n 1 || true)"
if [ -z "${JAR_FILE}" ] || [ ! -f "${JAR_FILE}" ]; then
  echo "错误：未找到 per-agent 可执行 jar（期望 target/per-agent-*.jar）"
  exit 1
fi
echo "      jar: ${JAR_FILE}"

# ---------------------------------------------------------------------
# [2/4] 组装 dist 目录：lib/ + bin/ + config/ + systemd/
# ---------------------------------------------------------------------
echo "[2/4] 组装分发包目录 ..."
rm -rf "${DIST_ROOT}"
mkdir -p "${DIST_DIR}/lib" "${DIST_DIR}/bin" "${DIST_DIR}/config" "${DIST_DIR}/systemd" "${DIST_DIR}/logs"

# 2.1 可执行 jar → lib/
cp "${JAR_FILE}" "${DIST_DIR}/lib/"

# 2.2 启动脚本 → bin/perpress-agent
#     行为：切换到安装目录（bin/ 的上一级）→ 写 PID 文件 → 前台 exec java -jar lib/*.jar；
#     前台 exec 保证 systemd（Restart=always）可正确跟踪进程，
#     手动场景可由 nohup 包裹后台运行（install.sh 无 systemd 分支即此用法）
cat > "${DIST_DIR}/bin/perpress-agent" <<'LAUNCHER'
#!/usr/bin/env bash
# PerPress Agent 启动脚本：cd 到安装目录后前台运行 lib 下的可执行 jar
set -e

# 切换到安装目录（本脚本位于 {install}/bin/）
cd "$(dirname "$0")/.."

# 定位 lib 下的可执行 jar（排除 sources 产物）
JAR="$(ls lib/per-agent-*.jar | grep -v 'sources' | head -n 1)"
if [ -z "${JAR}" ]; then
  echo "错误：lib/ 下未找到 per-agent-*.jar" >&2
  exit 1
fi

# 写 PID 文件（exec 后 java 复用当前进程 PID）后前台启动
mkdir -p logs
echo $$ > perpress-agent.pid
exec java -jar "${JAR}"
LAUNCHER
chmod +x "${DIST_DIR}/bin/perpress-agent"

# 2.3 配置模板 → config/application.yml
#     server-url / register-token 为占位符，由平台 install.sh 以 sed 替换；
#     其余默认值与 per-agent 工程的 application.yml 保持一致
cat > "${DIST_DIR}/config/application.yml" <<'YAML'
# PerPress 压测平台 Agent 配置（由平台安装脚本渲染）
spring:
  application:
    name: per-agent
  main:
    # 非常驻 Web 模式（不引入 spring-boot-starter-web）
    web-application-type: none
  task:
    scheduling:
      pool:
        # 所有定时任务共用单线程调度器，避免并发交错
        size: 1

per:
  agent:
    # 管理服务端地址（安装时由平台渲染）
    server-url: __SERVER_URL__
    # 注册令牌（安装时由平台渲染）
    register-token: __REGISTER_TOKEN__
    # 心跳上报间隔（秒）
    heartbeat-interval-seconds: 10
    # 任务指令轮询间隔（秒）
    poll-interval-seconds: 2
    # 心跳失联自停阈值（秒），达到后停止所有运行中的压测任务
    heartbeat-lost-stop-seconds: 60
    # 任务指令异步执行线程数（PREPARE/START 下载与启动在独立线程执行，与调度线程隔离）
    task-worker-threads: 1
    # 文件/引擎下载：请求超时（秒）
    download-timeout-seconds: 60
    # 文件/引擎下载：连接超时（秒）
    download-connect-timeout-seconds: 5
    # 本地工作目录：node_key 持久化、引擎目录 engine/、任务目录 tasks/
    data-dir: ./agent-data
YAML

# 2.4 systemd 单元文件示例 → systemd/
cat > "${DIST_DIR}/systemd/perpress-agent.service" <<'UNIT'
# PerPress Agent systemd 单元文件示例
# 安装路径：/etc/systemd/system/perpress-agent.service，随后执行：
#   systemctl daemon-reload && systemctl enable --now perpress-agent
[Unit]
Description=PerPress Load Test Agent
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/perpress-agent
ExecStart=/opt/perpress-agent/bin/perpress-agent
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

# ---------------------------------------------------------------------
# [3/4] 打包为 tar.gz（顶层目录 perpress-agent/，解压即得安装目录内容）
# ---------------------------------------------------------------------
echo "[3/4] 打包分发包 ..."
tar -czf "${OUT_TGZ}" -C "${DIST_ROOT}" perpress-agent

# ---------------------------------------------------------------------
# [4/4] 输出结果与放置提示
# ---------------------------------------------------------------------
echo "[4/4] 完成: ${OUT_TGZ} ($(du -h "${OUT_TGZ}" | cut -f1))"
echo ""
echo "请复制到 per-server 的 {per.storage.dir}/agent-dist/ 下，例如："
echo "  mkdir -p per-server/server-data/agent-dist"
echo "  cp \"${OUT_TGZ}\" per-server/server-data/agent-dist/per-agent-dist.tar.gz"
echo "之后节点即可执行平台「节点管理」页的一键安装命令完成部署"
