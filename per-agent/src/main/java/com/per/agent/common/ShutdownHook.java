package com.per.agent.common;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 进程退出清理钩子：随 Spring 容器关闭触发（SIGTERM/System.exit 均会走到）。
 * <p>M1 仅输出清理日志；M2 扩展：停止压测任务、清理任务运行标记等。</p>
 */
@Slf4j
@Component
public class ShutdownHook {

    /**
     * 容器销毁时的清理动作：M1 仅记录退出日志。
     */
    @PreDestroy
    public void cleanup() {
        log.info("[ShutdownHook] Agent 进程退出，执行清理（M1 仅日志）");
    }
}
