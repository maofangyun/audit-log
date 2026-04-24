package com.example.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置
 *
 * <p>显式声明 {@link ObjectMapper} Bean，供 {@link com.example.demo.aspect.AuditLogAspect}
 * 和 {@link com.example.demo.controller.AuditTestController} 通过构造器注入使用。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
