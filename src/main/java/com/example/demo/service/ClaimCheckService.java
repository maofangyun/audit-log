package com.example.demo.service;

import com.example.demo.config.MinioConfig;
import com.example.demo.util.KryoSerializer;
import com.example.demo.util.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Claim Check 核心服务
 *
 * <p>职责：判断 before/after 数据的序列化体积，决定是否执行 Claim Check 卸载：
 * <ul>
 *   <li>&lt; 100KB → 原对象直接内联到 JSON 骨架</li>
 *   <li>&gt;= 100KB → 调用 {@link MinioUtils} 上传至 MinIO，返回 Claim Check 指针</li>
 * </ul>
 *
 * <p>序列化由 {@link KryoSerializer} 负责，存储操作由 {@link MinioUtils} 负责，
 * 本类只保留业务决策逻辑。
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
     * 对 before + after 两个对象整体进行 Claim Check 决策。
     */
    public Map<String, Object> processDetailsPayload(String traceId, Object before, Object after) {
         // 使用高效的流式合并序列化
         byte[] combined = KryoSerializer.serializeCombined(before, after);

         if (combined.length < THRESHOLD_BYTES) {
             return null;
         }

         // ── 异步化改造核心：预生成 URL ─────────────────────
         String objectName = buildObjectName("details", traceId);
         String url = "minio://" + minioConfig.getBucket() + "/" + objectName;
         
         // 提交后台异步处理（压缩 + 上传）
         storageExecutor.execute(() -> {
             try {
                 byte[] compressed = compress(combined);
                 log.info("[ClaimCheck-Async] {} 存储就绪: {}B -> {}B", objectName, combined.length, compressed.length);
                 minioUtils.upload(minioConfig.getBucket(), objectName, compressed);
             } catch (Exception e) {
                 log.error("[ClaimCheck-Async] 存储失败: {}", e.getMessage());
             }
         });

         // 立即返回指针，不阻塞日志落盘
         return buildPointer(url);
    }

         /**
         * 从 MinIO 下载大对象内容，自动执行 GZIP 解压和 Kryo 反序列化。
     *
     * @param minioUrl Claim Check 指针（minio://bucket/objectName）
     * @return 还原后的 Java 对象（合并包则返回包含 oldState/newState 的 Map）
     */
    public Object downloadPayload(String minioUrl) {
        String[] parts = minioUtils.parseUrl(minioUrl);
        byte[] compressedData = minioUtils.download(parts[0], parts[1]);
        
        // 1. GZIP 解压
        byte[] rawData = decompress(compressedData);
        String objectName = parts[1];

        // 2. 还原逻辑
        if (objectName.contains("details-")) {
            // 还原合并包：拆分并反序列化 before/after
            byte[][] splitData = KryoSerializer.split(rawData);
            Map<String, Object> result = new HashMap<>();
            result.put("oldState", KryoSerializer.deserialize(splitData[0]));
            result.put("newState", KryoSerializer.deserialize(splitData[1]));
            return result;
        }
        
        // 还原单对象
        return KryoSerializer.deserialize(rawData);
    }

    // ── 私有辅助方法 ───────────────────────────────────────────────

    /**
     * GZIP 压缩（优化：使用 BEST_SPEED 提升吞吐量）
     */
    private byte[] compress(byte[] data) {
        if (data == null || data.length == 0) return data;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            // 使用 BEST_SPEED (级别 1)
            // 理由：对于大报文审计日志，I/O 是瓶颈，但不能让 CPU 成为死穴
            java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(bos) {
                { def.setLevel(java.util.zip.Deflater.BEST_SPEED); }
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
        if (compressedData == null || compressedData.length == 0) return compressedData;
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
     * 统一上传逻辑（含压缩）
     */
    private String uploadWithCompress(String traceId, byte[] data) {
        byte[] compressed = compress(data);
        String objectName = buildObjectName("details", traceId);
        log.info("[ClaimCheck] {} 压缩完成: {}B -> {}B ({}%)",
                "details", data.length, compressed.length, (compressed.length * 100 / data.length));
        return minioUtils.upload(minioConfig.getBucket(), objectName, compressed);
    }

    /**
     * 构造 Claim Check 指针 Map。
     */
    private Map<String, Object> buildPointer(String url) {
        Map<String, Object> pointer = new HashMap<>();
        pointer.put("_storage", "MINIO");
        pointer.put("_url", url);
        return pointer;
    }
}
