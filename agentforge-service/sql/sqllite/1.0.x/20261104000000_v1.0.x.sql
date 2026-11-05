-- 2026.11.04：新增 Agent 运行中追加消息持久化队列表
CREATE TABLE IF NOT EXISTS ai_agent_session_message_append (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    status      INTEGER NOT NULL DEFAULT 1,
    queued_at   DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
