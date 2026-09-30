#!/usr/bin/env bash
# =====================================================================
# PerPress 本地一键启动：MySQL(检查) → per-server → per-agent → per-web
# 日志：logs/*.log；进程号：.pids/；配套停止脚本：scripts/stop-all.sh
# 用法：scripts/start-all.sh [--no-agent|--no-web 可选跳过某端]
# =====================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"; PIDS="$ROOT/.pids"
mkdir -p "$LOGS" "$PIDS"

# 检查端口是否已有服务在监听（lsof）
port_alive() { lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

# 等待 HTTP 地址可达（$1=url $2=超时秒），成功返回 0
wait_http() {
  local i=0
  while [ "$i" -lt "$2" ]; do
    curl -s -o /dev/null --connect-timeout 2 "$1" && return 0
    sleep 2; i=$((i+2))
  done
  return 1
}

# ---------- 1. MySQL ----------
if port_alive 3306; then
  echo "[1/4] MySQL 已在运行(3306)"
else
  echo "[1/4] 尝试启动 MySQL 容器 mysql-container..."
  docker start mysql-container 2>/dev/null || {
    echo "  未找到 mysql-container，请手动启动 MySQL(3306) 后重试"; exit 1;
  }
  sleep 3; port_alive 3306 || { echo "  MySQL 启动失败"; exit 1; }
fi

# ---------- 2. per-server ----------
if port_alive 8080; then
  echo "[2/4] per-server 已在运行(8080)，跳过"
else
  echo "[2/4] 启动 per-server..."
  (cd "$ROOT/per-server" && nohup mvn -q spring-boot:run >"$LOGS/server.log" 2>&1 & echo $! >"$PIDS/server")
  if wait_http "http://localhost:8080/api/auth/login" 90; then
    echo "  per-server 启动成功: http://localhost:8080 (日志 logs/server.log)"
  else
    echo "  per-server 启动超时，查看 logs/server.log"; exit 1
  fi
fi

# ---------- 3. per-agent ----------
if [ "${1:-}" != "--no-agent" ]; then
  echo "[3/4] 启动 per-agent..."
  (cd "$ROOT/per-agent" && nohup mvn -q spring-boot:run >"$LOGS/agent.log" 2>&1 & echo $! >"$PIDS/agent")
  sleep 8
  if grep -q "恢复会话成功\|注册成功" "$LOGS/agent.log" 2>/dev/null; then
    echo "  per-agent 已连接平台 (日志 logs/agent.log)"
  else
    echo "  per-agent 启动中，稍后查看 logs/agent.log"
  fi
else
  echo "[3/4] 跳过 per-agent"
fi

# ---------- 4. per-web ----------
if [ "${1:-}" != "--no-web" ]; then
  if port_alive 5173; then
    echo "[4/4] per-web 已在运行(5173)，跳过"
  else
    echo "[4/4] 启动 per-web(vite dev)..."
    (cd "$ROOT/per-web" && nohup npm run dev >"$LOGS/web.log" 2>&1 & echo $! >"$PIDS/web")
    if wait_http "http://localhost:5173" 30; then
      echo "  per-web 启动成功: http://localhost:5173 (admin/admin123)"
    else
      echo "  per-web 启动中，查看 logs/web.log"
    fi
  fi
else
  echo "[4/4] 跳过 per-web"
fi

echo "全部完成。停止: scripts/stop-all.sh"
