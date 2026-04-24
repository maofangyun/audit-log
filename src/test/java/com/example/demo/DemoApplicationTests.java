package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring 应用上下文加载测试
 *
 * <p>验证所有 Bean 是否能正确装配，用于快速发现配置错误。
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoApplicationTests {

    @Test
    void contextLoads() {
        // 仅验证 Spring 上下文能正常启动，无需额外断言
    }
}
