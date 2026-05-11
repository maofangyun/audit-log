-- =============================================================
-- audit_logs 表：审计日志主表（纯元数据，无业务 payload）
-- =============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGINT          PRIMARY KEY,                             -- 雪花 ID（Java 生成）
    timestamp       TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,      -- 事件时间（带时区）
    trace_id        VARCHAR(64),                                             -- 链路追踪 ID
    user_id         VARCHAR(128),                                            -- 操作用户
    action          VARCHAR(255)    NOT NULL,                                -- 操作动作，如 CREATE/UPDATE
    resource_type   VARCHAR(100),                                            -- 资源类型，如 ORDER/USER
    resource_id     VARCHAR(255),                                            -- 资源唯一标识
    business_key    JSONB,                                                   -- 业务主键（JSONB格式，用于灵活的多键精确查询）
    status          VARCHAR(20)     DEFAULT 'SUCCESS'                        -- SUCCESS / FAIL
    -- ℹ️  details / is_large_payload 已移除：所有 before/after 数据统一由 audit_log_items 子表承载
);

-- 索引：按时间范围查询
CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs (timestamp DESC);
-- 索引：按用户查询
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id   ON audit_logs (user_id);
-- 索引：按资源查询
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource  ON audit_logs (resource_type, resource_id);
-- 索引：按业务主键查询（基于 JSONB 的 GIN 倒排索引，极速匹配多键值）
CREATE INDEX IF NOT EXISTS idx_audit_logs_biz_key   ON audit_logs USING GIN (business_key);

-- =============================================================
-- audit_log_items 表：审计日志子表（一对多，存储 before/after 每个元素）
--
-- payload 字段有两种形态：
--   小对象（< 100KB）：完整 JSON，如 {"id":1,"name":"张三"}
--   大对象（>= 100KB）：MinIO 指针，如 {"_storage":"MINIO","_url":"minio://bucket/path"}
-- =============================================================
CREATE TABLE IF NOT EXISTS audit_log_items (
    id              BIGSERIAL       PRIMARY KEY,
    audit_log_id    BIGINT          NOT NULL,                                -- 关联主表 ID（不设外键，两表均通过日志文件异步写入）
    item_index      INT             NOT NULL,                                -- 集合元素序号，0-based（单对象固定为 0）
    direction       VARCHAR(10)     NOT NULL,                                -- 'before' 或 'after'
    item_type       VARCHAR(255),                                            -- 元素 Java 类型全限定名（便于反序列化恢复）
    payload         JSONB           NOT NULL                                 -- 真实 JSON 或 MinIO 指针 JSON
);

-- 索引：按主表 ID 查子项（最高频查询）
CREATE INDEX IF NOT EXISTS idx_items_audit_log_id ON audit_log_items (audit_log_id);
-- 索引：按方向过滤（查询某条记录的 before 或 after）
CREATE INDEX IF NOT EXISTS idx_items_direction    ON audit_log_items (audit_log_id, direction);
