package com.per.server.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * Agent 安装服务：动态生成目标机一键安装 bash 脚本（install.sh），
 * 并提供 Agent 分发包路径约定（{per.storage.dir}/agent-dist/per-agent-dist.tar.gz）
 */
@Service
@RequiredArgsConstructor
public class AgentInstallService {

    /** 安装脚本内服务端地址占位符（渲染时替换） */
    private static final String PLACEHOLDER_SERVER = "__PER_SERVER_URL__";

    /** 安装脚本内注册 token 占位符（渲染时替换） */
    private static final String PLACEHOLDER_TOKEN = "__PER_TOKEN__";

    private final NodeService nodeService;
    private final FileStorageService storageService;

    /**
     * 生成 Agent 一键安装 bash 脚本：root 检查 → Java 17/21 检测/自动安装 →
     * 下载并解压分发包 → sed 替换 config/application.yml 占位符（__SERVER_URL__/__REGISTER_TOKEN__）→
     * systemd 或 nohup+rc.local 启动。server/token 缺省时取平台 base-url 与当前注册 token
     *
     * @param server 平台服务端地址（可空，默认 per.server.base-url）
     * @param token  节点注册 token（可空，默认当前注册 token）
     * @return 完整 bash 安装脚本文本
     */
    public String buildInstallScript(String server, String token) {
        String serverUrl = StringUtils.hasText(server) ? server : storageService.getBaseUrl();
        String registerToken = StringUtils.hasText(token) ? token : nodeService.getRegisterToken();
        return SCRIPT_TEMPLATE
                .replace(PLACEHOLDER_SERVER, sanitize(serverUrl))
                .replace(PLACEHOLDER_TOKEN, sanitize(registerToken));
    }

    /**
     * 获取 Agent 分发包路径：{per.storage.dir}/agent-dist/per-agent-dist.tar.gz
     * （由 scripts/build-agent-dist.sh 构建后手工放置）
     *
     * @return Agent 分发包文件路径
     */
    public Path agentDistPath() {
        return storageService.agentDistPath();
    }

    /**
     * 清理注入到 bash 双引号内的值：移除引号/反斜杠/反引号/美元符等危险字符，防注入
     *
     * @param value 原始值（服务端地址或 token）
     * @return 仅含安全字符的值
     */
    private String sanitize(String value) {
        return value == null ? "" : value
                .replace("\"", "")
                .replace("\\", "")
                .replace("`", "")
                .replace("$", "")
                .replace(";", "")
                .replace(" ", "");
    }

    /**
     * 安装脚本模板：占位符 __PER_SERVER_URL__ / __PER_TOKEN__ 由服务端渲染替换；
     * 目标机为 Linux（Ubuntu/CentOS 等），需 root 执行
     */
    private static final String SCRIPT_TEMPLATE = """
            #!/usr/bin/env bash
            # =============================================================
            # PerPress Agent 一键安装脚本（由平台动态生成）
            # 目标环境：Linux（Ubuntu/CentOS 等），需 root 权限
            # 行为：安装 Java 17 → 下载分发包 → 写配置 → systemd/nohup 常驻启动
            # =============================================================
            set -e

            SERVER_URL="__PER_SERVER_URL__"
            REGISTER_TOKEN="__PER_TOKEN__"
            INSTALL_DIR="/opt/perpress-agent"

            echo "==> PerPress Agent 安装开始（server: ${SERVER_URL}）"

            # 1) 必须 root 执行
            if [ "$(id -u)" -ne 0 ]; then
              echo "错误：请以 root 身份运行（推荐：curl -fsSL ... | sudo bash）"
              exit 1
            fi

            # 2) 检测 Java 17/21，缺失则尝试 apt-get/yum 自动安装 OpenJDK 17
            JAVA_OK=0
            if command -v java >/dev/null 2>&1; then
              if java -version 2>&1 | grep -qE 'version "(17|21)[.]'; then
                JAVA_OK=1
                echo "==> 检测到 Java 17/21，跳过安装"
              fi
            fi
            if [ "${JAVA_OK}" -ne 1 ]; then
              echo "==> 未检测到 Java 17/21，尝试自动安装 OpenJDK 17..."
              INSTALLED=0
              if command -v apt-get >/dev/null 2>&1; then
                if apt-get update -y && apt-get install -y openjdk-17-jre-headless; then
                  INSTALLED=1
                fi
              elif command -v yum >/dev/null 2>&1; then
                if yum install -y java-17-openjdk-headless; then
                  INSTALLED=1
                fi
              fi
              if [ "${INSTALLED}" -ne 1 ] || ! java -version 2>&1 | grep -qE 'version "(17|21)[.]'; then
                echo "错误：Java 17/21 自动安装失败，请手动安装（如：apt-get install -y openjdk-17-jre-headless 或 yum install -y java-17-openjdk-headless）后重新运行本脚本"
                exit 1
              fi
            fi

            # 3) 创建目录并下载 Agent 分发包（curl -f 失败即终止，--retry 重试 3 次）
            command -v curl >/dev/null 2>&1 || { echo "错误：缺少 curl，请先安装 curl"; exit 1; }
            mkdir -p "${INSTALL_DIR}"
            DIST_FILE="$(mktemp /tmp/perpress-agent-dist.XXXXXX)"
            echo "==> 下载 Agent 分发包..."
            if ! curl -f --retry 3 -o "${DIST_FILE}" "${SERVER_URL}/agent/dist/v$(date +%s)"; then
              echo "错误：Agent 分发包下载失败（请确认平台已放置分发包且地址可达：${SERVER_URL}）"
              exit 1
            fi
            if [ ! -s "${DIST_FILE}" ]; then
              echo "错误：Agent 分发包内容为空，请检查平台 {per.storage.dir}/agent-dist/per-agent-dist.tar.gz"
              exit 1
            fi
            tar -xzf "${DIST_FILE}" -C "${INSTALL_DIR}"
            rm -f "${DIST_FILE}"

            # 解压目录扁平化：分发包若带顶层目录（perpress-agent/），将内容合并覆盖到安装根。
            # 注意不能用 mv（目标已存在同名目录时 mv 会把源目录嵌套进目标，如 lib/lib/，
            # 导致升级时旧 jar 残留、新程序不生效），cp -a 覆盖合并才是正确语义。
            if [ -d "${INSTALL_DIR}/perpress-agent" ]; then
              echo "==> 检测到分发包含顶层目录，合并覆盖到安装根..."
              cp -a "${INSTALL_DIR}/perpress-agent/." "${INSTALL_DIR}/"
              rm -rf "${INSTALL_DIR}/perpress-agent"
            fi

            # 清理旧节点身份与停止标记：重装视为全新安装——
            # 若保留旧 node.key（该节点可能已被管理员删除并吊销），新 Agent 心跳会收到 4090 立即自停，
            # 导致"安装成功但节点不出现在平台"；STOPPED 标记同理（被删除机器自停时写入）。
            # 引擎目录 engine/ 与历史任务数据 tasks/ 不动，避免重复下载 86MB 引擎。
            if [ -f "${INSTALL_DIR}/agent-data/node.key" ] || [ -f "${INSTALL_DIR}/agent-data/STOPPED" ]; then
              echo "==> 检测到旧节点身份/停止标记，清理后以全新身份注册..."
              rm -f "${INSTALL_DIR}/agent-data/node.key" "${INSTALL_DIR}/agent-data/STOPPED"
            fi

            # 4) 渲染配置：分发包内 config/application.yml 模板占位符替换
            CONF="${INSTALL_DIR}/config/application.yml"
            if [ ! -f "${CONF}" ]; then
              echo "错误：分发包缺少 ${CONF}，分发包不完整"
              exit 1
            fi
            sed -i "s|__SERVER_URL__|${SERVER_URL}|g" "${CONF}"
            sed -i "s|__REGISTER_TOKEN__|${REGISTER_TOKEN}|g" "${CONF}"
            echo "==> 配置写入完成：${CONF}"

            # 5) 常驻启动：优先 systemd 托管（Restart=always），否则 nohup + /etc/rc.local 自启
            if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
              echo "==> 使用 systemd 托管 Agent..."
              cat > /etc/systemd/system/perpress-agent.service <<'UNIT'
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
              systemctl daemon-reload
              systemctl enable perpress-agent
              systemctl restart perpress-agent
            else
              echo "==> 未检测到 systemd，使用 nohup 后台启动..."
              if pgrep -f "per-agent-.*[.]jar" >/dev/null 2>&1; then
                echo "==> 检测到已有 Agent 进程，先停止再重启..."
                pkill -f "per-agent-.*[.]jar" || true
                sleep 1
              fi
              nohup "${INSTALL_DIR}/bin/perpress-agent" >> "${INSTALL_DIR}/agent.out" 2>&1 &
              sleep 1
              echo "==> Agent 已后台启动（PID: $(cat "${INSTALL_DIR}/perpress-agent.pid" 2>/dev/null || echo unknown)，日志：${INSTALL_DIR}/agent.out）"
              # 写入 /etc/rc.local 实现开机自启（幂等；已有 exit 0 时插入其前）
              RC_LOCAL="/etc/rc.local"
              touch "${RC_LOCAL}"
              chmod +x "${RC_LOCAL}" || true
              if grep -q "perpress-agent/bin/perpress-agent" "${RC_LOCAL}"; then
                echo "==> 开机自启已配置：${RC_LOCAL}"
              elif grep -q "^exit 0" "${RC_LOCAL}"; then
                sed -i '/^exit 0$/i nohup /opt/perpress-agent/bin/perpress-agent >> /opt/perpress-agent/agent.out 2>&1 &' "${RC_LOCAL}"
                echo "==> 已写入开机自启：${RC_LOCAL}"
              else
                {
                  echo ""
                  echo "# PerPress Agent 自启"
                  echo "nohup /opt/perpress-agent/bin/perpress-agent >> /opt/perpress-agent/agent.out 2>&1 &"
                } >> "${RC_LOCAL}"
                echo "==> 已写入开机自启：${RC_LOCAL}（请确认系统会执行 rc.local）"
              fi
            fi

            # 6) 完成
            echo "==> 安装完成，节点将自动注册到平台（可到平台「节点管理」页面查看）"
            """;
}
