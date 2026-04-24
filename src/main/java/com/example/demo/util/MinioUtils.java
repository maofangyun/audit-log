package com.example.demo.util;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 操作工具类
 *
 * <p>封装与 MinIO 对象存储的所有交互，包括：
 * <ul>
 *   <li>幂等建桶</li>
 *   <li>字节数组上传</li>
 *   <li>对象下载</li>
 *   <li>URL 格式解析</li>
 * </ul>
 *
 * <p>MinIO URL 格式约定：{@code minio://bucket/objectName}
 */
@Component
public class MinioUtils {

    private static final Logger log = LoggerFactory.getLogger(MinioUtils.class);

    /** MinIO URL 协议前缀 */
    private static final String MINIO_SCHEME = "minio://";

    /** 上传失败时的占位 URL 前缀，确保审计记录不丢失 */
    private static final String UPLOAD_ERROR_PREFIX = "minio://upload-error/";

    private final MinioClient minioClient;

    public MinioUtils(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 幂等建桶：若桶不存在则创建，已存在则跳过。
     * MinIO 不可用时仅打印警告，不阻止应用启动。
     *
     * @param bucket 桶名称
     */
    public void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinioUtils] 桶已创建: {}", bucket);
            } else {
                log.info("[MinioUtils] 桶已存在: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("[MinioUtils] 桶初始化失败（MinIO 服务可能尚未就绪）: {}", e.getMessage());
        }
    }

    /**
     * 将字节数组上传至指定桶，返回 MinIO URL。
     *
     * <p>上传失败时返回错误占位 URL（{@code minio://upload-error/objectName}），
     * 确保审计日志的 details 字段始终有值，不丢失审计记录。
     *
     * @param bucket     目标桶名称
     * @param objectName 对象路径（如 {@code traceId/label-uuid.bin}）
     * @param data       待上传的字节数组
     * @return MinIO URL，格式为 {@code minio://bucket/objectName}
     */
    public String upload(String bucket, String objectName, byte[] data) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(new ByteArrayInputStream(data), data.length, -1)
                            .contentType("application/octet-stream")
                            .build()
            );
            String url = MINIO_SCHEME + bucket + "/" + objectName;
            log.info("[MinioUtils] 上传成功: {}", url);
            return url;
        } catch (Exception e) {
            log.error("[MinioUtils] 上传失败: {}", e.getMessage(), e);
            return UPLOAD_ERROR_PREFIX + objectName;
        }
    }

    /**
     * 从 MinIO 下载对象，返回字节数组。
     *
     * @param bucket     桶名称
     * @param objectName 对象路径
     * @return 对象内容的字节数组
     * @throws RuntimeException 下载失败时抛出
     */
    public byte[] download(String bucket, String objectName) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            log.error("[MinioUtils] 下载失败: bucket={}, object={}, error={}", bucket, objectName, e.getMessage(), e);
            throw new RuntimeException(
                    String.format("MinIO 下载失败: minio://%s/%s", bucket, objectName), e);
        }
    }

    /**
     * 将 MinIO URL 解析为 [bucket, objectName] 数组。
     *
     * @param minioUrl 格式为 {@code minio://bucket/objectName} 的 URL
     * @return 长度为 2 的字符串数组：{@code [0]} 为 bucket，{@code [1]} 为 objectName
     * @throws IllegalArgumentException URL 格式非法时抛出
     */
    public String[] parseUrl(String minioUrl) {
        if (minioUrl == null || !minioUrl.startsWith(MINIO_SCHEME)) {
            throw new IllegalArgumentException("非法的 MinIO URL 格式: " + minioUrl);
        }
        String path = minioUrl.substring(MINIO_SCHEME.length());
        int slashIndex = path.indexOf('/');
        if (slashIndex < 0) {
            throw new IllegalArgumentException("MinIO URL 缺少对象路径: " + minioUrl);
        }
        return new String[]{path.substring(0, slashIndex), path.substring(slashIndex + 1)};
    }
}
