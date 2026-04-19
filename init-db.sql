CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    action VARCHAR(255),
    resource_type VARCHAR(100),
    resource_id VARCHAR(100),
    old_object TEXT,
    new_object TEXT,
    original_message TEXT
);
