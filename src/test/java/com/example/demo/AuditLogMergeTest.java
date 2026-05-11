package com.example.demo;

import com.example.demo.log.AuditMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注解合并集成测试
 *
 * <p>验证子类方法注解与父类类注解的合并行为（由近及远优先级）。
 * 同时验证双管道日志架构下，before / after 数据进入子项日志。
 */
@SpringBootTest
class AuditLogMergeTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private ch.qos.logback.core.read.ListAppender<ILoggingEvent> auditLogAppender;
    private ch.qos.logback.core.read.ListAppender<ILoggingEvent> itemsLogAppender;

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "auditAsyncPool")
        @Primary
        public Executor syncAuditPool() {
            return Runnable::run;
        }

        @Bean(name = "auditStoragePool")
        @Primary
        public Executor syncStoragePool() {
            return Runnable::run;
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT_LOG_NAME");
        auditLogAppender = newListAppender("MERGE_AUDIT_LIST");
        auditLogger.detachAppender("MERGE_AUDIT_LIST");
        auditLogger.addAppender(auditLogAppender);

        Logger itemsLogger = (Logger) LoggerFactory.getLogger("AUDIT_ITEMS_LOG_NAME");
        itemsLogAppender = newListAppender("MERGE_ITEMS_LIST");
        itemsLogger.detachAppender("MERGE_ITEMS_LIST");
        itemsLogger.addAppender(itemsLogAppender);
    }

    @Test
    void testAnnotationMerging() throws Exception {
        mockMvc.perform(post("/test/merge")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 1. 验证主表日志：action / resourceType 来自子类方法注解
        ILoggingEvent mainEvent = waitForLog(auditLogAppender, "CHILD_ACTION", 2000);
        assertNotNull(mainEvent, "应该产生 CHILD_ACTION 主表日志");

        AuditMessage msg = objectMapper.readValue(mainEvent.getMessage(), AuditMessage.class);
        assertEquals("CHILD_ACTION", msg.getAction(), "Action 应该来自子类方法注解");
        assertEquals("CHILD_TYPE", msg.getResourceType(), "ResourceType 应该来自子类方法注解");

        // 2. 主表日志不再有 details 字段
        assertFalse(mainEvent.getMessage().contains("\"details\""), "主表日志不应包含 details");

        // 3. 验证子项日志：before / after 来自父类类注解中的 oldObjectSpEL / newObjectSpEL
        List<ILoggingEvent> items = waitForItems(2, 2000);
        assertEquals(2, items.size(), "注解合并场景应产生 before + after 共 2 条子项日志");

        ILoggingEvent beforeEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"before\"")).findFirst().orElseThrow();
        ILoggingEvent afterEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"after\"")).findFirst().orElseThrow();

        assertTrue(beforeEvent.getMessage().contains("PARENT_OLD"),
                "before payload 应包含父类 oldObjectSpEL 解析的值 'PARENT_OLD'");
        assertTrue(afterEvent.getMessage().contains("PARENT_NEW"),
                "after payload 应包含父类 newObjectSpEL 解析的值 'PARENT_NEW'");
    }

    // ── 辅助方法 ────────────────────────────────────────────────────────

    private ch.qos.logback.core.read.ListAppender<ILoggingEvent> newListAppender(String name) {
        var appender = new ch.qos.logback.core.read.ListAppender<ILoggingEvent>();
        appender.setName(name);
        appender.start();
        return appender;
    }

    private ILoggingEvent waitForLog(ch.qos.logback.core.read.ListAppender<ILoggingEvent> appender,
                                     String keyword, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ILoggingEvent found = appender.list.stream()
                    .filter(e -> e.getMessage().contains(keyword))
                    .findFirst().orElse(null);
            if (found != null) return found;
            Thread.sleep(50);
        }
        return null;
    }

    private List<ILoggingEvent> waitForItems(int expectedCount, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (itemsLogAppender.list.size() >= expectedCount) {
                return List.copyOf(itemsLogAppender.list);
            }
            Thread.sleep(50);
        }
        return List.copyOf(itemsLogAppender.list);
    }
}
