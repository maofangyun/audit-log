package com.example.demo.service;

import com.example.demo.log.AuditItemMessage;
import com.example.demo.util.KryoSerializer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 审计日志子项服务
 *
 * <p>职责：将 before / after 对象拆行序列化，写入 {@code AUDIT_ITEMS_LOG_NAME} 日志文件，
 * 由 Vector 异步采集写入 {@code audit_log_items} 子表。
 *
 * <p>与主表流水线完全对等：
 * <pre>
 * 主表：AuditMessage JSON → AUDIT_LOG_NAME  → /var/log/app/audit/*.log      → Vector → audit_logs
 * 子表：AuditItemMessage  → AUDIT_ITEMS_LOG → /var/log/app/audit/items/*.log → Vector → audit_log_items
 * </pre>
 *
 * <p>统一存储模型：
 * <ul>
 *   <li>单个 POJO → 包装为单元素列表，写入 1 条日志（itemIndex = 0）</li>
 *   <li>{@link Collection} → 展开每个元素，每个元素写入 1 条日志</li>
 *   <li>null → 跳过，不写入任何日志</li>
 * </ul>
 *
 * <p>每个元素独立执行 Claim Check 决策：
 * <ul>
 *   <li>元素 Kryo 序列化字节 &lt; 100KB → Jackson 序列化为 JSON 写入 payload</li>
 *   <li>元素 Kryo 序列化字节 &gt;= 100KB → 异步上传至 MinIO，payload 写入指针 JSON</li>
 * </ul>
 */
@Service
public class AuditItemService {

    /**
     * 子项日志专用 Logger，对应 logback-spring.xml 中的 {@code AUDIT_ITEMS_LOG_NAME} 配置。
     */
    private static final Logger AUDIT_ITEMS_LOGGER = LoggerFactory.getLogger("AUDIT_ITEMS_LOG_NAME");

    private static final Logger log = LoggerFactory.getLogger(AuditItemService.class);

    /** 单元素 Claim Check 阈值：100KB */
    private static final int THRESHOLD_BYTES = 100 * 1024;

    private final ObjectMapper objectMapper;
    private final ClaimCheckService claimCheckService;

    public AuditItemService(ObjectMapper objectMapper,
                            ClaimCheckService claimCheckService) {
        this.objectMapper = objectMapper;
        this.claimCheckService = claimCheckService;
    }

    /**
     * 将 before 或 after 对象拆行写入子项日志。
     *
     * @param auditLogId 主表记录 ID（关联字段，无数据库外键约束）
     * @param obj        before 或 after 对象（单体 POJO / Collection / null）
     * @param direction  {@code "before"} 或 {@code "after"}
     * @param traceId    链路追踪 ID，大对象上传时用于构造 MinIO 路径
     */
    public void saveItems(long auditLogId, Object obj, String direction, String traceId) {
        if (obj == null) return;

        List<Object> elements = toList(obj);
        if (elements.isEmpty()) return;

        for (int i = 0; i < elements.size(); i++) {
            Object el = elements.get(i);
            try {
                Object resolvedPayload = resolvePayload(el, traceId, direction, i);
                AuditItemMessage msg = AuditItemMessage.builder()
                        .auditLogId(auditLogId)
                        .itemIndex(i)
                        .direction(direction)
                        .itemType(el != null ? el.getClass().getName() : null)
                        .payload(resolvedPayload)
                        .build();
                AUDIT_ITEMS_LOGGER.info(objectMapper.writeValueAsString(msg));
            } catch (JsonProcessingException e) {
                log.error("[AuditItem] 序列化失败 direction={} idx={}: {}", direction, i, e.getMessage());
            }
        }

        log.debug("[AuditItem] auditLogId={} direction={} items={}", auditLogId, direction, elements.size());
    }

    // ── 私有辅助方法 ────────────────────────────────────────────────────

    /**
     * 对单个元素决策 payload：
     * <ul>
     *   <li>小对象 → 元素本身（由 Jackson 序列化入 JSON）</li>
     *   <li>大对象 → MinIO 指针 Map {@code {"_storage":"MINIO","_url":"minio://..."}}</li>
     * </ul>
     */
    private Object resolvePayload(Object el, String traceId, String direction, int idx) {
        if (el == null) return null;

        byte[] kryoBytes = KryoSerializer.serialize(el);
        if (kryoBytes.length < THRESHOLD_BYTES) {
            // 小对象：直接返回原对象，由外层 Jackson 序列化为 JSON
            return el;
        }
        // 大对象：异步上传至 MinIO，立即返回指针 Map
        String url = claimCheckService.uploadItemAsync(traceId, direction, idx, kryoBytes);
        return Map.of("_storage", "MINIO", "_url", url);
    }

    /**
     * 将任意对象统一转换为列表：
     * <ul>
     *   <li>{@link Collection} → 转为 {@link ArrayList}（保留顺序）</li>
     *   <li>其他 → 包装为单元素 {@link List}</li>
     * </ul>
     */
    private List<Object> toList(Object obj) {
        if (obj instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        return List.of(obj);
    }
}
