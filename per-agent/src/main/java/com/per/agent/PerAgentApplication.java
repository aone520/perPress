package com.per.agent;

import java.util.concurrent.CountDownLatch;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PerPress 压测平台 Agent 模块启动入口。
 * <p>以非 Web 模式（WebApplicationType.NONE）运行，职责：向管理服务端注册、
 * 定时心跳上报本机资源、轮询任务指令（M1 任务恒空）、引擎自动部署框架（M1 预留）。</p>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PerAgentApplication {

    /**
     * JVM 主入口：以非 Web 模式启动 Spring 容器，主线程阻塞等待进程关闭信号；
     * 收到信号后通过 SpringApplication.exit 优雅停机，并以退出码调用 System.exit 结束进程。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PerAgentApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        // 非 Web 模式下主线程需要显式保活：等待 JVM 关闭钩子（SIGTERM/SIGINT）释放信号量
        CountDownLatch stopSignal = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stopSignal::countDown, "per-agent-stop-signal"));
        try {
            stopSignal.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }
}
