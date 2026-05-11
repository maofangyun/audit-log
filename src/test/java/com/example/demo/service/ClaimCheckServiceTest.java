package com.example.demo.service;

import com.example.demo.config.MinioConfig;
import com.example.demo.util.MinioUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link ClaimCheckService} 单元测试
 *
 * <p>验证 {@code uploadItemAsync} 的核心行为：
 * <ul>
 *   <li>立即返回预生成的 MinIO URL（不阻塞）</li>
 *   <li>提交异步任务后，执行器调用压缩上传</li>
 *   <li>URL 格式符合预期：{@code minio://bucket/traceId/direction-itemN-uuid.bin}</li>
 * </ul>
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
        when(minioUtils.upload(anyString(), anyString(), any(byte[].class)))
                .thenAnswer(inv -> "minio://audit-bucket/" + inv.getArgument(1));

        // 让异步 Executor 同步执行，以便在测试中同步验证 MinIO 调用
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(storageExecutor).execute(any(Runnable.class));
    }

    // ── uploadItemAsync 测试 ────────────────────────────────────────

    @Test
    void uploadItemAsync_returnsUrlImmediately() {
        byte[] kryoBytes = "some kryo bytes".getBytes();

        String url = claimCheckService.uploadItemAsync("trace-1", "after", 0, kryoBytes);

        // URL 应立即返回，格式正确
        assertNotNull(url, "URL 不应为 null");
        assertTrue(url.startsWith("minio://audit-bucket/trace-1/after-item0-"),
                "URL 格式应为 minio://bucket/traceId/direction-itemN-uuid.bin，实际为：" + url);
        assertTrue(url.endsWith(".bin"), "URL 应以 .bin 结尾");
    }

    @Test
    void uploadItemAsync_triggersMinioUpload() {
        byte[] kryoBytes = "some kryo bytes".getBytes();

        claimCheckService.uploadItemAsync("trace-2", "before", 1, kryoBytes);

        // Executor 同步执行后，MinIO upload 应被调用一次
        verify(minioUtils, times(1)).upload(
                eq("audit-bucket"), anyString(), any(byte[].class));
    }

    @Test
    void uploadItemAsync_differentDirectionAndIdx_urlContainsCorrectSegments() {
        byte[] kryoBytes = new byte[64];

        String urlBefore = claimCheckService.uploadItemAsync("trace-3", "before", 2, kryoBytes);
        String urlAfter  = claimCheckService.uploadItemAsync("trace-3", "after",  5, kryoBytes);

        assertTrue(urlBefore.contains("/before-item2-"), "before URL 应包含 'before-item2-'");
        assertTrue(urlAfter.contains("/after-item5-"),  "after URL 应包含 'after-item5-'");
    }

    @Test
    void uploadItemAsync_minioUploadFails_doesNotThrow() {
        when(minioUtils.upload(anyString(), anyString(), any(byte[].class)))
                .thenThrow(new RuntimeException("MinIO unavailable"));

        byte[] kryoBytes = "payload".getBytes();

        // 上传失败不应向调用方抛出异常（内部 catch 处理）
        assertDoesNotThrow(() ->
                claimCheckService.uploadItemAsync("trace-4", "after", 0, kryoBytes));
    }
}
