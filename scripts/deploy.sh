#!/usr/bin/env bash
# =====================================================================
# PerPress 线上一键部署：构建 server jar + 前端 dist → docker compose 拉起
# mysql + per-server + nginx 三容器，访问 http://<主机IP> (admin/admin123)
# 用法：
#   scripts/deploy.sh                        # 全量构建并部署
#   PER_PUBLIC_URL=http://1.2.3.4 scripts/deploy.sh   # 指定平台对外地址
#   scripts/deploy.sh --skip-build           # 跳过构建（仅重启容器）
# 前置：Docker + docker compose v2、JDK17/Maven/Node（仅构建时需要）
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/deploy"

echo "==> [1/4] 构建后端 jar（per-server）"
if [ "${1:-}" != "--skip-build" ]; then
  (cd "$ROOT/per-server" && mvn -q clean package -DskipTests)
  cp "$ROOT/per-server/target/per-server-"*.jar "$DEPLOY/per-server.jar"

  echo "==> [2/4] 构建前端（per-web dist）"
  (cd "$ROOT/per-web" && npm install --no-audit --no-fund && npm run build)
  rm -rf "$DEPLOY/dist" && cp -r "$ROOT/per-web/dist" "$DEPLOY/dist"
else
  echo "==> [1-2/4] 跳过构建（--skip-build）"
fi

# 生成/保留 .env（首次生成默认值，已存在不覆盖）
if [ ! -f "$DEPLOY/.env" ]; then
  cat > "$DEPLOY/.env" <<EOF
# PerPress 部署环境配置（重复执行 deploy.sh 不会覆盖本文件）
MYSQL_ROOT_PASSWORD=perpress@2026
PER_HTTP_PORT=80
# 平台对外访问地址：Agent 安装命令与文件下载前缀，部署后请改为实际 IP/域名
PER_PUBLIC_URL=${PER_PUBLIC_URL:-http://127.0.0.1}
EOF
  echo "==> 已生成 $DEPLOY/.env（可修改后重新执行 deploy.sh --skip-build）"
fi

echo "==> [3/4] 拉起容器（mysql + server + nginx）"
(cd "$DEPLOY" && docker compose up -d --build)

echo "==> [4/4] 等待服务就绪..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null --connect-timeout 2 "http://localhost:${PER_HTTP_PORT:-80}/"; then
    echo "部署完成： http://<主机IP>:${PER_HTTP_PORT:-80}  (admin/admin123)"
    echo "后续操作："
    echo "  - 修改平台对外地址: 编辑 deploy/.env 的 PER_PUBLIC_URL 后执行 scripts/deploy.sh --skip-build"
    echo "  - 上传 JMeter 引擎包: 平台「引擎管理」上传并发布（Agent 自动部署）"
    echo "  - Agent 分发包: 执行 scripts/build-agent-dist.sh 后将产物放到平台 {per.storage.dir}/agent-dist/"
    exit 0
  fi
  sleep 2
done
echo "服务未在 60s 内就绪，排查: docker compose -f deploy/docker-compose.yml logs"
exit 1
