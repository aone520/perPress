#!/usr/bin/env bash
# =====================================================================
# PerPress Agent 节点一键部署脚本（在每台压测机上执行）
#
# 使用方式：
#   bash deploy-agent.sh <服务端URL> <注册令牌> [CPU限制] [内存限制]
#
# 示例：
#   bash deploy-agent.sh http://192.168.1.100:8080 a1b2c3d4...
#   bash deploy-agent.sh http://192.168.1.100:8080 a1b2c3d4... 8 16G
#
# 如果已有 .env 文件，也可以不传参数直接执行：bash deploy-agent.sh
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---------- 颜色输出 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------- 1. 解析参数 ----------
SERVER_URL="${1:-}"
REGISTER_TOKEN="${2:-}"
CPU_LIMIT="${3:-4}"
MEM_LIMIT="${4:-8G}"

# 如果命令行没传，尝试从 .env 读取
if [[ -z "$SERVER_URL" && -f .env ]]; then
  set -a; source .env; set +a
  SERVER_URL="${PER_AGENT_SERVER_URL:-}"
  REGISTER_TOKEN="${PER_AGENT_REGISTER_TOKEN:-}"
  CPU_LIMIT="${PER_AGENT_CPU_LIMIT:-4}"
  MEM_LIMIT="${PER_AGENT_MEM_LIMIT:-8G}"
fi

# ---------- 2. 参数校验 ----------
[[ -n "$SERVER_URL" ]]    || error "服务端地址不能为空。用法: bash deploy-agent.sh <服务端URL> <注册令牌>"
[[ -n "$REGISTER_TOKEN" ]] || error "注册令牌不能为空。用法: bash deploy-agent.sh <服务端URL> <注册令牌>"

# 去除尾部斜杠
SERVER_URL="${SERVER_URL%/}"

# localhost 检查（容器内 localhost 指向容器自身，无法访问宿主机服务）
if [[ "$SERVER_URL" == *"localhost"* ]] || [[ "$SERVER_URL" == *"127.0.0.1"* ]]; then
  warn "服务端地址包含 localhost/127.0.0.1，容器内可能无法访问宿主机服务"
  warn "请确认使用了正确的宿主机 IP（如 192.168.x.x 或 host.docker.internal）"
  read -p "是否继续？(y/N) " -n 1 -r
  echo
  [[ $REPLY =~ ^[Yy]$ ]] || exit 1
fi

# ---------- 3. 环境检查 ----------
info "检查运行环境 ..."
command -v docker >/dev/null 2>&1 || error "未安装 Docker，请先安装 Docker Engine"
docker compose version >/dev/null 2>&1 || error "Docker Compose V2 不可用，请升级 Docker"

# 检查 jar 包
if [[ ! -f per-agent.jar ]]; then
  error "per-agent.jar 不存在，请先执行 bash build.sh 构建，或将 deploy 目录完整复制到压测机"
fi

# ---------- 4. 网络连通性检查 ----------
info "检查与服务端连通性: $SERVER_URL ..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "$SERVER_URL/api/scripts?page=1&size=1" 2>/dev/null || echo "000")
if [[ "$HTTP_CODE" == "000" ]]; then
  error "无法连接服务端 $SERVER_URL，请检查地址、防火墙和网络"
fi
info "服务端可达（HTTP $HTTP_CODE）"

# ---------- 5. 写入 .env ----------
info "写入 .env 配置 ..."
cat > .env <<EOF
# 由 deploy-agent.sh 自动生成
PER_AGENT_SERVER_URL=${SERVER_URL}
PER_AGENT_REGISTER_TOKEN=${REGISTER_TOKEN}
PER_AGENT_CPU_LIMIT=${CPU_LIMIT}
PER_AGENT_MEM_LIMIT=${MEM_LIMIT}
EOF

# ---------- 6. 启动 Agent 容器 ----------
echo ""
info "启动 per-agent 容器（CPU 限制: ${CPU_LIMIT}核，内存限制: ${MEM_LIMIT}） ..."
docker compose -f docker-compose.agent.yml down --remove-orphans 2>/dev/null || true
docker compose -f docker-compose.agent.yml up -d

# ---------- 7. 等待并检查注册状态 ----------
echo ""
info "等待 Agent 注册（最多 30 秒） ..."
sleep 10

# 检查容器是否正常运行
CONTAINER_STATUS=$(docker inspect -f '{{.State.Status}}' per-agent 2>/dev/null || echo "not_found")
if [[ "$CONTAINER_STATUS" != "running" ]]; then
  error "Agent 容器未正常运行（状态: $CONTAINER_STATUS），查看日志：docker logs per-agent"
fi

echo ""
echo "========================================"
echo -e "${GREEN}  PerPress Agent 节点部署完成！${NC}"
echo "========================================"
echo ""
echo "  服务端地址: $SERVER_URL"
echo "  CPU 限制:   ${CPU_LIMIT} 核"
echo "  内存限制:   ${MEM_LIMIT}"
echo ""
echo "  Agent 将在服务端「节点管理」页面自动出现（约 10 秒）"
echo ""
echo "  查看日志:   docker logs -f per-agent"
echo "  停止节点:   docker compose -f docker-compose.agent.yml down"
echo "  重启节点:   docker compose -f docker-compose.agent.yml restart"
echo ""
