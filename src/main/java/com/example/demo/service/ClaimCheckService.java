package com.example.demo.service;

import com.example.demo.config.MinioConfig;
import com.example.demo.util.KryoSerializer;
import com.example.demo.util.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Claim Check 核心服务
 *
 * <p>
 * 职责：对单个审计元素执行 Claim Check 卸载决策：
 * <ul>
 * <li>调用方（{@link com.example.demo.service.AuditItemService}）负责判断元素大小</li>
 * <li>大对象（&gt;= 100KB）调用 {@link #uploadItemAsync} 异步压缩并上传至 MinIO，立即返回 URL</li>
 * </ul>
 *
 * <p>
 * 序列化由 {@link KryoSerializer} 负责，存储操作由 {@link MinioUtils} 负责，
 * 本类只保留存储决策与 I/O 逻辑。
 */
@Service
public class ClaimCheckService {

    private static final Logger log = LoggerFactory.getLogger(ClaimCheckService.class);

    /** 大对象阈值：100KB */
    private static final int THRESHOLD_BYTES = 100 * 1024;

    private final MinioUtils minioUtils;
    private final MinioConfig minioConfig;
    private final java.util.concurrent.Executor storageExecutor;

    public ClaimCheckService(MinioUtils minioUtils,
            MinioConfig minioConfig,
            @org.springframework.beans.factory.annotation.Qualifier("auditStoragePool") java.util.concurrent.Executor storageExecutor) {
        this.minioUtils = minioUtils;
        this.minioConfig = minioConfig;
        this.storageExecutor = storageExecutor;
    }

    /**
     * 异步上传单个审计元素（Kryo 字节）至 MinIO，立即返回 URL。
     *
     * <p>调用方已完成大小判断（>= 100KB），本方法只负责预生成 URL、提交后台压缩上传任务。
     *
     * @param traceId   链路追踪 ID，用于构造对象路径前缀
     * @param direction 'before' 或 'after'
     * @param idx       元素在集合中的序号（0-based）
     * @param kryoBytes 已由 {@link KryoSerializer#serialize(Object)} 生成的原始字节
     * @return MinIO 对象 URL，格式：{@code minio://bucket/traceId/direction-itemN-uuid.bin}
     */
    public String uploadItemAsync(String traceId, String direction, int idx, byte[] kryoBytes) {
        String objectName = buildObjectName(direction + "-item" + idx, traceId);
        String url = "minio://" + minioConfig.getBucket() + "/" + objectName;

        storageExecutor.execute(() -> {
            try {
                byte[] compressed = compress(kryoBytes);
                log.info("[ClaimCheck-Item] {} 存储就绪: {}B -> {}B", objectName, kryoBytes.length, compressed.length);
                minioUtils.upload(minioConfig.getBucket(), objectName, compressed);
            } catch (Exception e) {
                log.error("[ClaimCheck-Item] 存储失败: {}", e.getMessage());
            }
        });

        return url;
    }

    /**
     * 从 MinIO 下载大对象内容，执行 GZIP 解压和 Kryo 反序列化，还原为原始 Java 对象。
     *
     * @param minioUrl Claim Check 指针（{@code minio://bucket/objectName}）
     * @return 还原后的 Java 对象
     */
    public Object downloadPayload(String minioUrl) {
        String[] parts = minioUtils.parseUrl(minioUrl);
        byte[] compressedData = minioUtils.download(parts[0], parts[1]);
        return KryoSerializer.deserialize(decompress(compressedData));
    }

    // ── 私有辅助方法 ───────────────────────────────────────────────

    /**
     * GZIP 压缩（优化：使用 BEST_SPEED 提升吞吐量）
     */
    private byte[] compress(byte[] data) {
        if (data == null || data.length == 0)
            return data;

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1024 * 1024); // 初始 1MB
        try {
            // 使用 BEST_SPEED (级别 1) 并且增加缓冲区大小到 64KB (默认仅 512)
            // 理由：对于大报文审计日志，I/O 是瓶颈，但不能让 CPU 成为死穴
            java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(bos, 65536) {
                {
                    def.setLevel(java.util.zip.Deflater.BEST_SPEED);
                }
            };
            gzos.write(data);
            gzos.finish();
            gzos.close();
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("[ClaimCheck] 压缩失败: {}", e.getMessage());
            return data;
        }
    }

    /**
     * GZIP 解压
     */
    private byte[] decompress(byte[] compressedData) {
        if (compressedData == null || compressedData.length == 0)
            return compressedData;
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(compressedData);
                java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(bis)) {
            return gzis.readAllBytes();
        } catch (Exception e) {
            log.error("[ClaimCheck] 解压失败（可能数据未压缩）: {}", e.getMessage());
            return compressedData;
        }
    }

    /**
     * 生成 MinIO 对象路径，格式：{@code traceId/label-uuid.bin}
     */
    private String buildObjectName(String label, String traceId) {
        return String.format("%s/%s-%s.bin", traceId, label, UUID.randomUUID());
    }



    /**
     * 初始化时自动检查并建桶
     */
    @jakarta.annotation.PostConstruct
    public void initBucket() {
        minioUtils.ensureBucketExists(minioConfig.getBucket());
    }
}
