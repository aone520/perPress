#!/usr/bin/env bash
# =====================================================================
# PerPress 主程序一键部署脚本
# 在 deploy 目录下执行：bash deploy-server.sh
#
# 功能：
#   1. 检查环境（Docker、.env 配置、jar 包）
#   2. 拉起 MySQL + per-server + Nginx
#   3. 等待服务就绪后查询并输出节点注册令牌
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

# ---------- 1. 环境检查 ----------
info "检查运行环境 ..."

command -v docker >/dev/null 2>&1 || error "未安装 Docker，请先安装 Docker Engine"
docker compose version >/dev/null 2>&1 || error "Docker Compose V2 不可用，请升级 Docker"

# 加载 .env
if [[ ! -f .env ]]; then
  warn ".env 文件不存在，从 .env.example 复制 ..."
  cp .env.example .env
  warn "请编辑 .env 修改 PER_PUBLIC_URL 等配置后重新执行本脚本"
  exit 1
fi
set -a; source .env; set +a

if [[ -z "${PER_PUBLIC_URL:-}" || "${PER_PUBLIC_URL}" == http://192.168.1.100:* ]]; then
  warn "请先编辑 .env 设置 PER_PUBLIC_URL 为实际服务器地址"
  exit 1
fi

# 检查 jar 包
for jar in per-server.jar; do
  if [[ ! -f "$jar" ]]; then
    error "$jar 不存在，请先执行 bash build.sh 构建镜像"
  fi
done

info "环境检查通过"
info "  PER_PUBLIC_URL = $PER_PUBLIC_URL"
info "  MySQL 密码     = ${MYSQL_ROOT_PASSWORD:0:3}***"

# ---------- 2. 拉起服务 ----------
echo ""
info "启动主程序容器（MySQL + per-server + Nginx） ..."
docker compose down --remove-orphans 2>/dev/null || true
docker compose up -d

# ---------- 3. 等待服务就绪 ----------
echo ""
info "等待 per-server 启动 ..."
SERVER_PORT="${PER_SERVER_PORT:-8080}"
MAX_WAIT=60
WAITED=0
while true; do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${SERVER_PORT}/api/scripts?page=1&size=1" 2>/dev/null || echo "000")
  if [[ "$HTTP_CODE" != "000" ]]; then
    info "per-server 已就绪（HTTP $HTTP_CODE）"
    break
  fi
  WAITED=$((WAITED + 2))
  if [[ $WAITED -ge $MAX_WAIT ]]; then
    error "per-server 启动超时（${MAX_WAIT}s），请检查日志：docker compose logs per-server"
  fi
  sleep 2
done

# ---------- 4. 查询注册令牌 ----------
echo ""
info "查询节点注册令牌 ..."

# 尝试从 MySQL 容器读取注册令牌
TOKEN=""
for i in $(seq 1 10); do
  TOKEN=$(docker exec per-mysql mysql -u root -p"${MYSQL_ROOT_PASSWORD:-perpress@2026}" \
    -N -e "SELECT config_value FROM per_press.sys_config WHERE config_key='register.token';" 2>/dev/null || echo "")
  if [[ -n "$TOKEN" ]]; then
    break
  fi
  sleep 2
done

echo ""
echo "========================================"
echo -e "${GREEN}  PerPress 主程序部署完成！${NC}"
echo "========================================"
echo ""
echo "  管理界面:  http://<服务器IP>${PER_HTTP_PORT:+:${PER_HTTP_PORT}}"
echo "  API 地址:  http://<服务器IP>:${SERVER_PORT}"
echo "  默认账号:  admin / admin123（首次登录需修改密码）"
echo ""
if [[ -n "$TOKEN" ]]; then
  echo "  节点注册令牌: ${GREEN}${TOKEN}${NC}"
  echo ""
  echo "  在压测机上部署 Agent 节点："
  echo "    bash deploy-agent.sh ${PER_PUBLIC_URL} ${TOKEN}"
else
  warn "未能自动查询注册令牌，请登录管理界面在「节点管理」中查看"
fi
echo ""
echo "  查看日志:  docker compose logs -f per-server"
echo "  停止服务:  docker compose down"
echo ""
