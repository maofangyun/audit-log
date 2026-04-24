-- =============================================================
-- audit_logs 表：Claim Check 模式核心存储表
-- 小对象（<100KB）：details 直接存储完整 JSON
-- 大对象（>=100KB）：details 存储 MinIO 指针 {"_storage":"MINIO","_url":"..."}
-- =============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGINT          PRIMARY KEY,                             -- 雪花 ID（Java 生成）
    timestamp       TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,      -- 事件时间（带时区）
    trace_id        VARCHAR(64),                                             -- 链路追踪 ID
    user_id         VARCHAR(128),                                            -- 操作用户
    action          VARCHAR(255)    NOT NULL,                                -- 操作动作，如 CREATE/UPDATE
    resource_type   VARCHAR(100),                                            -- 资源类型，如 ORDER/USER
    resource_id     VARCHAR(255),                                            -- 资源唯一标识
    business_id     VARCHAR(128),                                            -- 业务主键（用于秒级精确查询）
    status          VARCHAR(20)     DEFAULT 'SUCCESS',                       -- SUCCESS / FAIL
    is_large_payload BOOLEAN        NOT NULL DEFAULT FALSE,                  -- 是否为大对象（已卸载至 MinIO）
    details         JSONB                                                    -- 小对象存全量；大对象存 URL 指针
);

-- 索引：按时间范围查询
CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs (timestamp DESC);
-- 索引：按用户查询
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id   ON audit_logs (user_id);
-- 索引：按资源查询
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource  ON audit_logs (resource_type, resource_id);
-- 索引：按业务主键查询（秒级精确匹配）
CREATE INDEX IF NOT EXISTS idx_audit_logs_biz_id    ON audit_logs (business_id);
-- 索引：快速筛选大对象
CREATE INDEX IF NOT EXISTS idx_audit_logs_large     ON audit_logs (is_large_payload) WHERE is_large_payload = TRUE;
