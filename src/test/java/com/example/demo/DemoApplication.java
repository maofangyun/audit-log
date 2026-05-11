package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 审计日志服务启动入口
 *
 * <p>基于 Claim Check 模式的异步审计日志系统，支持大对象卸载至 MinIO，
 * 通过 Vector 管道将结构化日志写入 PostgreSQL。
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
