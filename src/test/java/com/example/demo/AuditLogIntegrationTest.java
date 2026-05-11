package com.example.demo;

import com.example.demo.controller.AuditTestController;
import com.example.demo.log.AuditMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计日志切面集成测试
 *
 * <p>验证双管道日志架构：
 * <ul>
 *   <li>主表（audit_logs）：AUDIT_LOG_NAME → 日志文件 → Vector → PG</li>
 *   <li>子表（audit_log_items）：AUDIT_ITEMS_LOG_NAME → 日志文件 → Vector → PG</li>
 *   <li>两表无外键约束，独立写入，顺序无关</li>
 * </ul>
 *
 * <p>测试通过捕获 Logback {@code ListAppender} 验证日志内容，不依赖真实数据库写入。
 * 使用同步线程池替换异步线程池，确保断言前日志已写入。
 * MinioClient 使用 Mock，避免依赖真实 MinIO 服务。
 */
@SpringBootTest
class AuditLogIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioClient minioClient;

    private MockMvc mockMvc;

    /** 捕获主表日志（AUDIT_LOG_NAME） */
    private ch.qos.logback.core.read.ListAppender<ILoggingEvent> auditLogAppender;
    /** 捕获子表日志（AUDIT_ITEMS_LOG_NAME） */
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

        @Bean
        @Primary
        public MinioClient mockMinioClient() {
            return mock(MinioClient.class);
        }

        @Bean(name = "testAuditHelper")
        public TestAuditHelper testAuditHelper() {
            return new TestAuditHelper();
        }
    }

    public static class TestAuditHelper {
        public String getOldValue(Long id, String type) {
            return "OLD_ORDER_" + id + "_" + type;
        }

        public String getNewValue(String result) {
            return result + "_PROCESSED";
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // 主表日志 appender
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT_LOG_NAME");
        auditLogAppender = newListAppender("AUDIT_LIST");
        auditLogger.detachAppender("AUDIT_LIST");
        auditLogger.addAppender(auditLogAppender);

        // 子表日志 appender
        Logger itemsLogger = (Logger) LoggerFactory.getLogger("AUDIT_ITEMS_LOG_NAME");
        itemsLogAppender = newListAppender("ITEMS_LIST");
        itemsLogger.detachAppender("ITEMS_LIST");
        itemsLogger.addAppender(itemsLogAppender);

        reset(minioClient);
    }

    // ── 测试 1：小对象 → 子项日志有 before + after 各 1 条，payload 为真实 JSON ──

    @Test
    void testSmallPayload_itemLogsWrittenAsJson_noMinioUpload() throws Exception {
        AuditTestController.UserDto user = new AuditTestController.UserDto("1", "Alice", 25);
        mockMvc.perform(post("/test/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk());

        // 1. 验证主表日志
        ILoggingEvent mainEvent = waitForLog(auditLogAppender, "UPDATE_USER", 2000);
        assertNotNull(mainEvent, "未找到 UPDATE_USER 主表日志");

        AuditMessage msg = objectMapper.readValue(mainEvent.getMessage(), AuditMessage.class);
        assertEquals("UPDATE_USER", msg.getAction());
        assertEquals("USER", msg.getResourceType());
        assertEquals("1", msg.getResourceId());

        // 主表 JSON 不含 details / isLargePayload
        assertFalse(mainEvent.getMessage().contains("\"details\""), "主表日志不应含 details");
        assertFalse(mainEvent.getMessage().contains("isLargePayload"), "主表日志不应含 isLargePayload");

        // 2. 验证子表日志：before + after 各 1 条
        List<ILoggingEvent> items = waitForItems(2, 2000);
        assertEquals(2, items.size(), "小对象应产生 before + after 共 2 条子项日志");

        ILoggingEvent afterEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"after\"")).findFirst().orElseThrow();
        ILoggingEvent beforeEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"before\"")).findFirst().orElseThrow();

        // after payload 包含 Alice（真实 JSON，非 MinIO 指针）
        assertTrue(afterEvent.getMessage().contains("Alice"), "after payload 应包含 Alice");
        assertFalse(afterEvent.getMessage().contains("_storage"), "小对象不应为 MinIO 指针");

        // before payload 包含 Tom（fetchUserFromDb 返回的旧数据）
        assertTrue(beforeEvent.getMessage().contains("Tom"), "before payload 应包含 Tom");

        // 3. MinIO 不应被调用
        verify(minioClient, never()).putObject(any());
    }

    // ── 测试 2：大对象 → 子项日志 payload 为 MinIO 指针，触发 MinIO 上传 ──────

    @Test
    void testLargePayload_itemPayloadIsMinioPointer_minioUploadCalled() throws Exception {
        String largeField = "A".repeat(150_000);
        AuditTestController.UserDto user = new AuditTestController.UserDto("2", largeField, 30);
        mockMvc.perform(post("/test/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(user)))
                .andExpect(status().isOk());

        // 1. 主表日志存在
        ILoggingEvent mainEvent = waitForLog(auditLogAppender, "UPDATE_USER", 2000);
        assertNotNull(mainEvent, "未找到 UPDATE_USER 主表日志");

        AuditMessage msg = objectMapper.readValue(mainEvent.getMessage(), AuditMessage.class);
        assertEquals("UPDATE_USER", msg.getAction());
        assertFalse(mainEvent.getMessage().contains("\"details\""), "主表日志不应含 details");

        // 2. 子项日志：before + after 各 1 条
        List<ILoggingEvent> items = waitForItems(2, 2000);
        assertEquals(2, items.size(), "大对象应产生 before + after 共 2 条子项日志");

        // after 条目 payload 应为 MinIO 指针
        ILoggingEvent afterEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"after\"")).findFirst().orElseThrow();
        String afterJson = afterEvent.getMessage();
        assertTrue(afterJson.contains("_storage"), "大对象 after payload 应为 MinIO 指针");
        assertTrue(afterJson.contains("_url"), "MinIO 指针应含 _url");
        assertTrue(afterJson.contains("minio://"), "MinIO URL 应以 minio:// 开头");

        // 3. MinIO 应被调用
        verify(minioClient, atLeastOnce()).putObject(any());
    }

    // ── 测试 3：SpEL Bean 调用 → 子项日志包含 Bean 方法解析的值 ─────────────

    @Test
    void testSpelBeanInvocation_itemsContainResolvedStrings() throws Exception {
        mockMvc.perform(post("/test/spel/bean")
                        .param("id", "99")
                        .param("type", "PAYMENT"))
                .andExpect(status().isOk());

        ILoggingEvent mainEvent = waitForLog(auditLogAppender, "TEST_SPEL_BEAN", 2000);
        assertNotNull(mainEvent, "未找到 TEST_SPEL_BEAN 主表日志");
        assertFalse(mainEvent.getMessage().contains("\"details\""), "主表不应含 details");

        List<ILoggingEvent> items = waitForItems(2, 2000);
        assertEquals(2, items.size(), "SpEL Bean 场景应产生 before + after 共 2 条子项日志");

        ILoggingEvent beforeEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"before\"")).findFirst().orElseThrow();
        ILoggingEvent afterEvent = items.stream()
                .filter(e -> e.getMessage().contains("\"direction\":\"after\"")).findFirst().orElseThrow();

        assertTrue(beforeEvent.getMessage().contains("OLD_ORDER_99_PAYMENT"),
                "before payload 应含 SpEL Bean 解析的旧值");
        assertTrue(afterEvent.getMessage().contains("RESULT_99_PROCESSED"),
                "after payload 应含 SpEL Bean 解析的新值");
    }

    // ── 测试 4：集合对象 → 子项日志按元素拆行，每个元素 1 条 ─────────────────

    @Test
    void testCollectionPayload_itemsExpandedPerElement() throws Exception {
        var auditItemService = webApplicationContext.getBean(
                com.example.demo.service.AuditItemService.class);

        List<String> collection = List.of("elem0", "elem1", "elem2");
        long fakeLogId = 999_001L;

        auditItemService.saveItems(fakeLogId, collection, "before", "trace-col-test");

        // 等待 3 条子项日志
        List<ILoggingEvent> items = waitForItems(3, 2000);
        assertEquals(3, items.size(), "集合 3 元素应产生 3 条子项日志");

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            ILoggingEvent itemEvent = items.stream()
                    .filter(e -> e.getMessage().contains("\"itemIndex\":" + idx))
                    .findFirst()
                    .orElse(null);
            assertNotNull(itemEvent, "应存在 itemIndex=" + idx + " 的日志条目");
            assertTrue(itemEvent.getMessage().contains("elem" + idx),
                    "itemIndex=" + idx + " 的 payload 应包含 elem" + idx);

            // 验证 auditLogId 正确写入
            JsonNode node = objectMapper.readTree(itemEvent.getMessage());
            assertEquals(fakeLogId, node.get("auditLogId").asLong(),
                    "auditLogId 应等于 " + fakeLogId);
        }
    }

    // ── 辅助方法 ────────────────────────────────────────────────────────

    private ch.qos.logback.core.read.ListAppender<ILoggingEvent> newListAppender(String name) {
        var appender = new ch.qos.logback.core.read.ListAppender<ILoggingEvent>();
        appender.setName(name);
        appender.start();
        return appender;
    }

    /** 轮询等待主/子表日志中包含关键词的条目，最多 timeoutMs */
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

    /** 轮询等待子表日志条目数量达到 expectedCount */
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
