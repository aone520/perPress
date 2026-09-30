#!/usr/bin/env bash
# =====================================================================
# PerPress 本地一键停止：per-web → per-server → per-agent（MySQL 不动）
# 用法：scripts/stop-all.sh
# =====================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIDS="$ROOT/.pids"

# 按 .pids 记录 + 端口 + 进程关键字三重兜底停止服务
stop_one() { # $1=名称 $2=pid文件名 $3=端口(0=不用端口) $4=pkill模式
  local pid=""
  [ -f "$PIDS/$2" ] && pid=$(cat "$PIDS/$2")
  # 杀 pid 记录进程及其子进程（mvn -> java 进程树）
  if [ -n "$pid" ]; then
    pkill -P "$pid" 2>/dev/null; kill "$pid" 2>/dev/null
  fi
  # 端口兜底
  if [ "$3" != "0" ]; then
    lsof -tiTCP:"$3" -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null
  fi
  # 进程关键字兜底
  [ -n "$4" ] && pkill -f "$4" 2>/dev/null
  rm -f "$PIDS/$2"
  echo "[OK] $1 已停止"
}

echo "停止 per-web..."
lsof -tiTCP:5173 -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null
if [ -f "$PIDS/web" ]; then kill "$(cat "$PIDS/web")" 2>/dev/null; rm -f "$PIDS/web"; fi
echo "[OK] per-web 已停止"

stop_one "per-server" "server" 8080 "per-server.*spring-boot:run"
stop_one "per-agent"  "agent"  0    "per-agent.*spring-boot:run"

echo "全部停止完成（MySQL 容器保持运行）。"
