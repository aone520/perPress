package com.per.agent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 线程池装配（M2）：注册任务工作线程池 Bean，
 * 用于异步执行 PREPARE/START 指令（下载脚本附件、启动 JMeter 子进程），与调度线程隔离。
 */
@Configuration
public class AgentExecutorConfig {

    /**
     * 任务工作线程池：默认单线程（可配 per.agent.task-worker-threads），守护线程，
     * 随 Spring 容器关闭自动 shutdown（destroyMethod）。
     *
     * @param properties Agent 配置
     * @return 任务工作线程池
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService taskWorkerExecutor(AgentProperties properties) {
        int threads = Math.max(1, properties.getTaskWorkerThreads());
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "task-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
