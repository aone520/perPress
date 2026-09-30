#!/usr/bin/env bash
# =====================================================================
# PerPress 本机一键 Docker 部署：平台 + 3 台 Agent 压力机 + 演示被测服务
#
# 在项目根目录执行：bash deploy/deploy-local.sh
#
# 脚本做 5 件事：
#   1. 编译 per-server / per-agent jar
#   2. 构建 Docker 镜像 per-server:latest / per-agent:latest
#   3. 从本机已部署引擎目录打包 engine.zip（供 server 启动引导注册）
#   4. 拷贝前端构建产物到 deploy/dist
#   5. 启动 docker-compose.local.yml 全套服务
#
# 启动完成后：
#   平台页面 http://localhost（admin/admin123），节点管理可见 3 台 Agent；
#   压测目标示例 http://per-target:9090/api/hello（容器内）或 http://localhost:9090（表单脚本用容器内地址）
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENGINE_SRC="$PROJECT_ROOT/per-agent/agent-data/engine"

echo "========================================"
echo "  PerPress 本机一键部署（平台 + 3 Agent）"
echo "========================================"

# ---------- 1. 编译两个 jar ----------
echo ""
echo "[1/5] 编译 per-server / per-agent ..."
cd "$PROJECT_ROOT/per-server" && mvn clean package -DskipTests -q
cd "$PROJECT_ROOT/per-agent" && mvn clean package -DskipTests -q
SERVER_JAR=$(ls "$PROJECT_ROOT"/per-server/target/per-server-*.jar | grep -v sources | head -1)
AGENT_JAR=$(ls "$PROJECT_ROOT"/per-agent/target/per-agent-*.jar | grep -v sources | head -1)
cp "$SERVER_JAR" "$SCRIPT_DIR/per-server.jar"
cp "$AGENT_JAR" "$SCRIPT_DIR/per-agent.jar"

# ---------- 2. 构建镜像 ----------
echo ""
echo "[2/5] 构建 Docker 镜像 ..."
docker build -f "$SCRIPT_DIR/Dockerfile.server" -t per-server:latest "$SCRIPT_DIR"
docker build -f "$SCRIPT_DIR/Dockerfile.agent" -t per-agent:latest "$SCRIPT_DIR"

# ---------- 3. 打包 JMeter 引擎 zip ----------
echo ""
echo "[3/5] 打包 JMeter 引擎 zip ..."
if [ ! -d "$ENGINE_SRC/bin" ]; then
  echo "错误：未找到本机引擎目录 $ENGINE_SRC（请先用开发环境安装一次引擎，或手动放置 engine.zip 到 deploy/）" >&2
  exit 1
fi
(cd "$ENGINE_SRC" && zip -qr "$SCRIPT_DIR/engine.zip" .)
echo "  engine.zip ($(du -h "$SCRIPT_DIR/engine.zip" | cut -f1))"

# ---------- 4. 前端产物 ----------
echo ""
echo "[4/5] 拷贝前端构建产物 ..."
if [ ! -f "$PROJECT_ROOT/per-web/dist/index.html" ]; then
  echo "前端 dist 不存在，先构建前端 ..."
  cd "$PROJECT_ROOT/per-web" && npm run build
fi
# 注意：只清空目录内容、保留目录本身（rm -rf 整个目录会换 inode，
# macOS Docker/OrbStack 的 bind mount 指向旧 inode，容器内将看不到新文件）
mkdir -p "$SCRIPT_DIR/dist"
rm -rf "$SCRIPT_DIR/dist"/*
cp -R "$PROJECT_ROOT/per-web/dist/." "$SCRIPT_DIR/dist/"
echo "  dist 就绪"

# ---------- 5. 启动全套 ----------
echo ""
echo "[5/5] 启动 docker compose（MySQL/Server/Nginx/被测服务/3 Agent）..."
cd "$SCRIPT_DIR"
docker compose -f docker-compose.local.yml down -v >/dev/null 2>&1 || true
docker compose -f docker-compose.local.yml up -d

echo ""
echo "========================================"
echo "  部署完成！"
echo "========================================"
echo ""
echo "  平台页面:   http://localhost        (admin / admin123)"
echo "  API 直连:   http://localhost:8180"
echo "  被测服务:   http://localhost:9090/api/hello"
echo ""
echo "  3 台 Agent 正在注册并自动部署 JMeter 引擎（首次约 1-2 分钟），"
echo "  可执行以下命令观察节点上线："
echo "    docker logs -f per-local-agent-1"
echo ""
echo "  停止/清理："
echo "    cd deploy && docker compose -f docker-compose.local.yml down      # 停止（保留数据）"
echo "    cd deploy && docker compose -f docker-compose.local.yml down -v   # 停止并清空数据"
