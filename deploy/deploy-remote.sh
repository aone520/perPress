#!/usr/bin/env bash
# =====================================================================
# PerPress 纯 Docker 命令部署脚本（不依赖 docker compose，只用 docker 命令）
# -----------------------------------------------------
# 流程：克隆/更新仓库 → docker build 构建镜像 → network/volume →
#       docker run 依次启动 mysql → server → web（nginx）
# 目标机只需 Docker（编译在容器内完成，无需本机 Maven/JDK/Node）。
#
# 用法一（任意目录，一行命令部署）：
#   curl -fsSL https://raw.githubusercontent.com/aone520/perPress/main/deploy/deploy-remote.sh | bash -s -- [选项]
#
# 用法二（已克隆仓库）：
#   bash deploy/deploy-remote.sh [选项]
#
# 选项：
#   <url>                 git 仓库地址（默认 https://github.com/aone520/perPress.git）
#   -d, --dir <path>      克隆/更新目录（默认 /tmp/perpress-remote 或脚本所在仓库根）
#   --http-port <port>    平台页面端口（默认 80）
#   --api-port <port>     API 直连端口（默认 8080）
#   --branch <name>       分支（默认 main）
#   --public-url <url>    平台对外地址（Agent 注册用，默认 http://<本机IP>:<api端口>）
#   --no-pull             已在仓库目录内执行时跳过 git 拉取
#
# 部署完成后：
#   平台页面 http://<主机>：<http端口>（admin / admin123）
#   压力机接入：节点管理页复制安装命令（install.sh）到目标机执行
#   引擎包：首次使用请在「引擎管理」上传 JMeter 引擎 zip
# =====================================================================
set -euo pipefail

REPO_DEFAULT="https://github.com/aone520/perPress.git"
REPO_URL="$REPO_DEFAULT"
CLONE_DIR=""
HTTP_PORT="80"
API_PORT="8080"
BRANCH="main"
PUBLIC_URL=""
NO_PULL=0
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-perpress@2026}"
MYSQL_DB="per_press"
NET_NAME="per-net"
MYSQL_CONTAINER="per-mysql"
SERVER_CONTAINER="per-server"   # 名称固定：nginx.conf 上游按 per-server 做 DNS 解析
WEB_CONTAINER="per-web"
MYSQL_VOLUME="per-mysql-data"
SERVER_VOLUME="per-server-data"

# ---------- 参数解析：支持位置参数仓库地址 + 任意顺序的选项 ----------
while [ $# -gt 0 ]; do
  case "$1" in
    -d|--dir) CLONE_DIR="$2"; shift 2;;
    --http-port) HTTP_PORT="$2"; shift 2;;
    --api-port) API_PORT="$2"; shift 2;;
    --branch) BRANCH="$2"; shift 2;;
    --public-url) PUBLIC_URL="$2"; shift 2;;
    --no-pull) NO_PULL=1; shift;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    http*|git@*) REPO_URL="$1"; shift;;
    *) echo "未知参数: $1（-h 查看帮助）" >&2; exit 1;;
  esac
done

# ---------- 定位仓库：优先脚本自身所在仓库（--no-pull / 已克隆场景） ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT=""
if [ -f "$SCRIPT_DIR/../Dockerfile.server.remote" ] || [ "$NO_PULL" = "1" ]; then
  REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
  echo "使用当前仓库目录: $REPO_ROOT"
  if [ "$NO_PULL" != "1" ] && git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "更新仓库（$BRANCH 分支）..."
    git -C "$REPO_ROOT" fetch origin "$BRANCH" --depth 1 2>/dev/null \
      && git -C "$REPO_ROOT" checkout -q "$BRANCH" \
      && git -C "$REPO_ROOT" reset -q --hard "origin/$BRANCH" \
      || echo "  仓库更新失败，使用本地现有代码继续"
  fi
else
  # 全新克隆（浅克隆加速）
  CLONE_DIR="${CLONE_DIR:-/tmp/perpress-remote}"
  echo "克隆仓库 $REPO_URL（$BRANCH 分支）到 $CLONE_DIR ..."
  if [ -d "$CLONE_DIR/.git" ]; then
    git -C "$CLONE_DIR" fetch origin "$BRANCH" --depth 1 2>/dev/null \
      && git -C "$CLONE_DIR" reset -q --hard "origin/$BRANCH" \
      || echo "  更新失败，使用目录内现有代码"
  else
    rm -rf "$CLONE_DIR"
    git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$CLONE_DIR"
  fi
  REPO_ROOT="$CLONE_DIR"
fi

cd "$REPO_ROOT"

# ---------- 推导平台对外地址（Agent 用其注册/心跳/下载引擎） ----------
if [ -z "$PUBLIC_URL" ]; then
  LOCAL_IP="$(ip route get 1 2>/dev/null | awk '{print $7; exit}' || hostname -I 2>/dev/null | awk '{print $1}' || echo 127.0.0.1)"
  PUBLIC_URL="http://${LOCAL_IP}:${API_PORT}"
fi

echo "========================================"
echo "  PerPress 纯 Docker 命令部署"
echo "  仓库: $REPO_ROOT"
echo "  页面端口: $HTTP_PORT | API 端口: $API_PORT"
echo "  对外地址: $PUBLIC_URL"
echo "========================================"

# ---------- 第 1 步：构建镜像（多阶段构建，容器内编译；首次约 5-10 分钟） ----------
echo ""
echo "[1/5] 构建镜像 per-server / per-web ..."
docker build -f Dockerfile.server.remote -t per-server:latest .
docker build -f Dockerfile.web.remote    -t per-web:latest .

# ---------- 第 2 步：创建网络与数据卷（已存在则复用，幂等） ----------
echo ""
echo "[2/5] 创建网络 $NET_NAME 与数据卷 ..."
docker network create "$NET_NAME" 2>/dev/null || echo "  网络 $NET_NAME 已存在，复用"
docker volume create "$MYSQL_VOLUME"   >/dev/null
docker volume create "$SERVER_VOLUME"  >/dev/null

# ---------- 第 3 步：启动 MySQL（utf8mb4 + 数据卷持久化；重复执行先删旧容器） ----------
echo ""
echo "[3/5] 启动 MySQL ..."
docker rm -f "$MYSQL_CONTAINER" 2>/dev/null || true
docker run -d --name "$MYSQL_CONTAINER" \
  --network "$NET_NAME" --restart always \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e MYSQL_DATABASE="$MYSQL_DB" \
  -e TZ=Asia/Shanghai \
  -v "$MYSQL_VOLUME":/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci

# ---------- 等待 MySQL 就绪（等价 compose 的 service_healthy） ----------
printf "等待 MySQL 就绪"
for i in $(seq 1 30); do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping -h localhost -p"$MYSQL_ROOT_PASSWORD" --silent >/dev/null 2>&1; then
    echo " OK"
    break
  fi
  [ "$i" = "30" ] && { echo ""; echo "错误：MySQL 90 秒未就绪"; docker logs --tail 30 "$MYSQL_CONTAINER"; exit 1; }
  printf "."
  sleep 3
done

# ---------- 第 4 步：启动 per-server（容器名固定 per-server，nginx 按名反代） ----------
echo ""
echo "[4/5] 启动 per-server ..."
docker rm -f "$SERVER_CONTAINER" 2>/dev/null || true
docker run -d --name "$SERVER_CONTAINER" \
  --network "$NET_NAME" --restart always \
  -p "${API_PORT}:8080" \
  -e TZ=Asia/Shanghai \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${MYSQL_CONTAINER}:3306/${MYSQL_DB}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false" \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e PER_STORAGE_DIR=/data \
  -e PER_SERVER_BASE_URL="$PUBLIC_URL" \
  -e PER_REGISTER_DEFAULT_TOKEN="${PER_REGISTER_TOKEN:-perpress-remote-token}" \
  -e PER_JWT_SECRET="${PER_JWT_SECRET:-perpress-secret-key-please-change-in-production-0123456789}" \
  -v "$SERVER_VOLUME":/data \
  per-server:latest

# ---------- 第 5 步：启动 per-web（nginx 已含静态页与反代配置） ----------
echo ""
echo "[5/5] 启动 per-web ..."
docker rm -f "$WEB_CONTAINER" 2>/dev/null || true
docker run -d --name "$WEB_CONTAINER" \
  --network "$NET_NAME" --restart always \
  -p "${HTTP_PORT}:80" \
  per-web:latest

# ---------- 就绪探测（server 启动 + 建表最长约 90s） ----------
echo ""
printf "等待平台就绪"
for i in $(seq 1 30); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:${API_PORT}/api/auth/login" -X POST -H 'Content-Type: application/json' -d '{}' 2>/dev/null || echo 000)
  if [ "$CODE" != "000" ]; then
    echo ""
    echo "========================================"
    echo "  部署完成！"
    echo "========================================"
    echo ""
    echo "  平台页面:   http://localhost:${HTTP_PORT}     (admin / admin123)"
    echo "  API 直连:   http://localhost:${API_PORT}"
    echo "  对外地址:   $PUBLIC_URL（压力机 Agent 用）"
    echo ""
    echo "  接入压力机："
    echo "    1. 平台「节点管理」复制安装命令（install.sh）"
    echo "    2. 到压力机执行即可自动注册（需能访问 $PUBLIC_URL）"
    echo "    3. 「引擎管理」上传 JMeter 引擎 zip，Agent 将自动部署引擎"
    echo ""
    echo "  常用操作（纯 docker 命令）："
    echo "    docker logs -f per-server                 # 看后端日志"
    echo "    docker restart per-server                 # 重启后端"
    echo "    docker rm -f per-web per-server per-mysql # 停止（数据保留在卷中）"
    echo "    docker volume rm $MYSQL_VOLUME $SERVER_VOLUME  # 彻底清空数据"
    exit 0
  fi
  printf "."
  sleep 6
done
echo ""
echo "警告：90 秒内平台未就绪，查看日志排查："
echo "  docker logs --tail 50 per-server"
exit 1
