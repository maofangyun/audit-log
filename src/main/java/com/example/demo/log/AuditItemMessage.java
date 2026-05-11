package com.example.demo.log;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志子项消息载体
 *
 * <p>该对象由 {@link com.example.demo.service.AuditItemService} 构造，
 * 经 Jackson 序列化为 JSON 后落盘至 items 日志文件，再由 Vector 采集写入 {@code audit_log_items} 表。
 *
 * <p>与 {@link AuditMessage} 的关系：
 * <ul>
 *   <li>{@code auditLogId} 关联主表记录（无数据库外键约束，两表均异步写入）</li>
 *   <li>{@code payload} 为真实 JSON 或 MinIO 指针 JSON（大对象场景）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditItemMessage {

    /** 关联的主表审计记录 ID */
    private Long auditLogId;

    /** 元素在集合中的序号（0-based，单对象固定为 0） */
    private int itemIndex;

    /** 方向：{@code "before"} 或 {@code "after"} */
    private String direction;

    /** 元素的 Java 类型全限定名（可选，便于反序列化恢复） */
    private String itemType;

    /**
     * 审计 payload：
     * <ul>
     *   <li>小对象（&lt; 100KB）：元素完整 JSON，如 {@code {"id":1,"name":"张三"}}</li>
     *   <li>大对象（&gt;= 100KB）：MinIO 指针，如 {@code {"_storage":"MINIO","_url":"minio://..."}}</li>
     * </ul>
     */
    private Object payload;
}
