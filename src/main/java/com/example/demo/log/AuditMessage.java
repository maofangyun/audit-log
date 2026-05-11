package com.example.demo.log;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志消息载体
 *
 * <p>该对象由 {@link com.example.demo.aspect.AuditLogAspect} 构造，
 * 经 Jackson 序列化为 JSON 后落盘至审计日志文件，再由 Vector 采集写入数据库。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code details} - 小对象时为完整 before/after 数据；大对象时为 Claim Check 指针 Map</li>
 *   <li>{@code isLargePayload} - 标记是否已执行 Claim Check 卸载至 MinIO</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMessage {

    /** 雪花算法全局唯一 ID */
    private Long   id;

    /** 链路追踪 ID */
    private String traceId;

    /** 操作人 ID */
    private String userId;

    /** 操作类型，如 UPDATE_USER */
    private String action;

    /** 资源类型，如 USER */
    private String resourceType;

    /** 资源 ID */
    private String resourceId;

    /** 业务主键（作为 JSONB 存储，用于极速索引） */
    private Object businessKey;

    /** 操作结果：SUCCESS / FAIL */
    private String status;

    // ℹ️ before/after 数据均存储于 audit_log_items 子表，此处不再冗余存储
}
