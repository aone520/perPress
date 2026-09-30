#!/usr/bin/env bash
# =====================================================================
# 制作 PerPress 增强版 JMeter 引擎包：
#   官方 apache-jmeter zip + jmeter-plugins 自定义线程组集
#   （jpgc-casutg：ConcurrencyThreadGroup / SteppingThreadGroup /
#    Throughput Shaping Timer，依赖 jmeter-plugins-cmn-jmeter）
# 产物：/tmp/per-engine-{version}-per1.zip，用于在平台「引擎管理」上传发布
#
# 插件直链来源（实测 curl -sI 返回 200）：
#   jmeter-plugins.org 官方插件仓库描述符（https://jmeter-plugins.org/repo/）
#   中 jpgc-casutg 2.10 的 downloadUrl/libs 字段，均托管于 Maven Central：
#     https://repo.maven.apache.org/maven2/kg/apc/jmeter-plugins-casutg/2.10/jmeter-plugins-casutg-2.10.jar
#     https://repo.maven.apache.org/maven2/kg/apc/jmeter-plugins-cmn-jmeter/0.7/jmeter-plugins-cmn-jmeter-0.7.jar
#   （旧路径 https://jmeter-plugins.org/files/.../CustomThreadGroups-*.jar 已 404 废弃，
#    2.x 起插件拆分为 jpgc-casutg 并发布到 Maven Central）
#
# 用法：./make-engine-package.sh [jmeter版本，默认 5.6.3] [--no-plugins]
#   --no-plugins：跳过插件下载，仅打包官方 JMeter（平台 M3 方案本就不依赖插件，
#                 插件仅为兼容用户自带、引用了 Custom Thread Groups 元素的 JMX 脚本）
# =====================================================================
set -euo pipefail

JMVER="5.6.3"
SKIP_PLUGINS=0
for arg in "$@"; do
  case "${arg}" in
    --no-plugins) SKIP_PLUGINS=1 ;;
    *) JMVER="${arg}" ;;
  esac
done

WORK=$(mktemp -d)
SRC_ZIP="/tmp/apache-jmeter-${JMVER}.zip"
OUT_ZIP="/tmp/per-engine-${JMVER}-per1.zip"

# 插件 jar 直链（Maven Central，版本随官方仓库描述符更新）
PLUGINS_VER="2.10"
CMN_VER="0.7"
CTG_URL="https://repo.maven.apache.org/maven2/kg/apc/jmeter-plugins-casutg/${PLUGINS_VER}/jmeter-plugins-casutg-${PLUGINS_VER}.jar"
CMN_URL="https://repo.maven.apache.org/maven2/kg/apc/jmeter-plugins-cmn-jmeter/${CMN_VER}/jmeter-plugins-cmn-jmeter-${CMN_VER}.jar"

echo "[1/5] 校验官方包: ${SRC_ZIP}"
[ -f "${SRC_ZIP}" ] || { echo "缺少官方包，请先: curl -L -o ${SRC_ZIP} https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMVER}.zip"; exit 1; }

echo "[2/5] 解压官方包"
unzip -q "${SRC_ZIP}" -d "${WORK}"

JM_DIR="${WORK}/apache-jmeter-${JMVER}"
[ -d "${JM_DIR}/bin" ] || { echo "解压后未找到 ${JM_DIR}/bin"; exit 1; }

echo "[3/5] 下载插件: Custom Thread Groups (jpgc-casutg) + 公共依赖 cmn-jmeter"
if [ "${SKIP_PLUGINS}" -eq 1 ]; then
  echo "      --no-plugins 已指定，跳过插件下载（仅官方包；平台 M3 方案不依赖插件，"
  echo "      插件仅为兼容用户自带、引用 Custom Thread Groups 元素的 JMX 脚本）"
else
  # 插件 jar 放 lib/ext/，公共依赖放 lib/（JMeter 插件加载约定）
  if ! curl -fsSL --retry 3 -o "${JM_DIR}/lib/ext/jmeter-plugins-casutg-${PLUGINS_VER}.jar" "${CTG_URL}"; then
    echo "警告：插件下载失败（${CTG_URL}），可加 --no-plugins 参数仅打包官方 JMeter"
    exit 1
  fi
  if ! curl -fsSL --retry 3 -o "${JM_DIR}/lib/jmeter-plugins-cmn-jmeter-${CMN_VER}.jar" "${CMN_URL}"; then
    echo "警告：插件依赖下载失败（${CMN_URL}），可加 --no-plugins 参数仅打包官方 JMeter"
    exit 1
  fi
fi

echo "[4/5] 写入版本标记"
echo "engine.version=${JMVER}-per1" > "${JM_DIR}/PERPRESS_ENGINE.txt"
if [ "${SKIP_PLUGINS}" -eq 0 ]; then
  echo "engine.plugins=jmeter-plugins-casutg-${PLUGINS_VER},jmeter-plugins-cmn-jmeter-${CMN_VER}" >> "${JM_DIR}/PERPRESS_ENGINE.txt"
else
  echo "engine.plugins=none" >> "${JM_DIR}/PERPRESS_ENGINE.txt"
fi

echo "[5/5] 重新打包: ${OUT_ZIP}"
(cd "${WORK}" && zip -qr "${OUT_ZIP}" "apache-jmeter-${JMVER}")

echo "完成: ${OUT_ZIP} ($(du -h "${OUT_ZIP}" | cut -f1))"
echo "请在平台「引擎管理」上传该 zip，版本号填 ${JMVER}-per1 并发布"
