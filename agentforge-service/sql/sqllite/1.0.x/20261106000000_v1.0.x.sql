-- 2026.11.06：新增可重连流式对话事件暂存表（SQLite）
CREATE TABLE IF NOT EXISTS ai_agent_session_message_event (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id  INTEGER NOT NULL,
    event_seq   INTEGER NOT NULL,
    event_type  TEXT    NOT NULL,
    payload     TEXT    NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (message_id, event_seq)
);
