#!/usr/bin/env bash
# =====================================================================
# PerPress 一键构建脚本：编译 jar + 构建 Docker 镜像
# 在项目根目录或 deploy 目录下执行：bash deploy/build.sh
# =====================================================================
set -euo pipefail

# 定位项目根目录（脚本所在目录的上一级）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "========================================"
echo "  PerPress Docker 镜像构建"
echo "  项目根目录: $PROJECT_ROOT"
echo "========================================"

# ---------- 1. 编译 per-server ----------
echo ""
echo "[1/4] 编译 per-server ..."
cd "$PROJECT_ROOT/per-server"
mvn clean package -DskipTests -q
SERVER_JAR=$(ls target/per-server-*.jar | grep -v sources | head -1)
echo "  产物: $SERVER_JAR"

# ---------- 2. 编译 per-agent ----------
echo ""
echo "[2/4] 编译 per-agent ..."
cd "$PROJECT_ROOT/per-agent"
mvn clean package -DskipTests -q
AGENT_JAR=$(ls target/per-agent-*.jar | grep -v sources | head -1)
echo "  产物: $AGENT_JAR"

# ---------- 3. 复制 jar 到 deploy 目录 ----------
echo ""
echo "[3/4] 复制 jar 到 deploy/ ..."
cp "$PROJECT_ROOT/per-server/$SERVER_JAR" "$SCRIPT_DIR/per-server.jar"
cp "$PROJECT_ROOT/per-agent/$AGENT_JAR" "$SCRIPT_DIR/per-agent.jar"
echo "  per-server.jar ($(du -h "$SCRIPT_DIR/per-server.jar" | cut -f1))"
echo "  per-agent.jar  ($(du -h "$SCRIPT_DIR/per-agent.jar" | cut -f1))"

# ---------- 4. 构建 Docker 镜像 ----------
echo ""
echo "[4/4] 构建 Docker 镜像 ..."
cd "$SCRIPT_DIR"

echo "  构建 per-server:latest ..."
docker build -f Dockerfile.server -t per-server:latest .

echo "  构建 per-agent:latest ..."
docker build -f Dockerfile.agent -t per-agent:latest .

echo ""
echo "========================================"
echo "  构建完成！"
echo "========================================"
echo ""
echo "镜像列表："
docker images | grep -E "per-(server|agent)" | head -5
echo ""
echo "下一步："
echo "  主程序部署:  cd deploy && cp .env.example .env  # 修改配置后 bash deploy-server.sh"
echo "  节点部署:    bash deploy-agent.sh <服务端URL> <注册令牌>"
