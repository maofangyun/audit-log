package com.example.demo.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 审计上下文持有器
 *
 * <p>基于 {@link ThreadLocal} 在同一请求线程内传递 before 快照，
 * 供 {@link com.example.demo.aspect.AuditLogAspect} 在方法执行后读取。
 *
 * <p><strong>注意：</strong>必须在请求处理完成后调用 {@link #clear()} 以释放资源，
 * 防止 Tomcat 线程池中的 ThreadLocal 泄漏。
 */
public final class AuditContextHolder {

    private static final ThreadLocal<JsonNode> OLD_OBJECT_HOLDER = new ThreadLocal<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AuditContextHolder() {
        // 工具类，禁止实例化
    }

    /**
     * 将 before 对象转换为 {@link JsonNode} 并存入当前线程上下文。
     *
     * @param oldObject 方法执行前的对象快照
     */
    public static void setOldObject(Object oldObject) {
        try {
            OLD_OBJECT_HOLDER.set(oldObject != null ? OBJECT_MAPPER.valueToTree(oldObject) : null);
        } catch (Exception e) {
            OLD_OBJECT_HOLDER.set(null);
        }
    }

    /**
     * 获取当前线程中存储的 before 快照。
     *
     * @return before 快照的 {@link JsonNode} 表示，或 {@code null}
     */
    public static JsonNode getOldObject() {
        return OLD_OBJECT_HOLDER.get();
    }

    /**
     * 清理当前线程的上下文，防止内存泄漏。
     */
    public static void clear() {
        OLD_OBJECT_HOLDER.remove();
    }
}
