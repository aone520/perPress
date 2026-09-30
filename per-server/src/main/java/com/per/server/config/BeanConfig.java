package com.per.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 通用 Bean 配置：提供密码加密等基础组件
 */
@Configuration
public class BeanConfig {

    /**
     * 创建 BCrypt 密码编码器（仅用于用户密码加密与比对）
     *
     * @return BCryptPasswordEncoder 实例
     */
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
