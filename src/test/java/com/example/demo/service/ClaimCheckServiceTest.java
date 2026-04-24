package com.example.demo.service;

import com.example.demo.config.MinioConfig;
import com.example.demo.util.MinioUtils;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ClaimCheckService} 单元测试
 *
 * <p>验证 Claim Check 的业务决策逻辑（小对象内联 vs 大对象卸载）。
 * {@link MinioUtils} 使用 Mock，底层 MinIO 操作不在本测试范围内。
 */
class ClaimCheckServiceTest {

    @Mock
    private MinioUtils minioUtils;

    @Mock
    private MinioConfig minioConfig;

    @Mock
    private java.util.concurrent.Executor storageExecutor;

    @InjectMocks
    private ClaimCheckService claimCheckService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(minioConfig.getBucket()).thenReturn("audit-bucket");
        // Mock upload 返回合法的 MinIO URL
        when(minioUtils.upload(anyString(), anyString(), any(byte[].class)))
                .thenAnswer(inv -> "minio://audit-bucket/" + inv.getArgument(1));

        // 让异步 Executor 同步执行，以便在测试中验证结果
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(storageExecutor).execute(any(Runnable.class));
        }

    // ── processDetailsPayload 测试 ─────────────────────────────────

    @Test
    void processDetailsPayload_smallBeforeAndAfter_returnsNull() {
        Map<String, Object> result = claimCheckService.processDetailsPayload("trace-2", "Before", "After");
        assertNull(result, "小对象不应触发 Claim Check，应返回 null");
    }

    @Test
    void processDetailsPayload_largeBeforeAndAfter_uploadsToMinioAndReturnsPointer() {
        String largeData = "Large data component. ".repeat(5_000);

        Map<String, Object> result = claimCheckService.processDetailsPayload("trace-2", largeData, largeData);

        assertNotNull(result, "大对象应返回 Claim Check 指针");
        assertEquals("MINIO", result.get("_storage"));
        String url = (String) result.get("_url");
        assertTrue(url.startsWith("minio://audit-bucket/trace-2/details-"), "URL 格式应符合预期");

        verify(minioUtils, times(1)).upload(anyString(), anyString(), any(byte[].class));
    }
}
