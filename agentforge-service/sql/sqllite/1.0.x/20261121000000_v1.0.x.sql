-- 2026.11.21：新增文件元数据表，支撑附件归属校验、会话引用与后续清理（SQLite）
CREATE TABLE IF NOT EXISTS ai_file_info (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name        TEXT    NOT NULL,
    origin_file_name TEXT             DEFAULT NULL,
    visit_url        TEXT    NOT NULL,
    file_type        TEXT             DEFAULT NULL,
    file_size        INTEGER          DEFAULT NULL,
    storage_mode     TEXT    NOT NULL DEFAULT 'local',
    create_user_id   INTEGER NOT NULL DEFAULT 1,
    is_deleted       INTEGER NOT NULL DEFAULT 0,
    create_time      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_file_info_user ON ai_file_info (create_user_id);

-- 2026.11.21：用户消息请求快照（含附件元信息），供历史回放渲染附件
ALTER TABLE ai_agent_session_message ADD COLUMN req TEXT DEFAULT NULL;
