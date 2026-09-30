package com.per.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PerPress 压测平台管理服务端启动类
 * 开启定时任务调度，用于节点心跳超时检测等周期性任务
 */
@SpringBootApplication
@EnableScheduling
public class PerServerApplication {

    /**
     * 应用入口方法
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PerServerApplication.class, args);
    }
}
