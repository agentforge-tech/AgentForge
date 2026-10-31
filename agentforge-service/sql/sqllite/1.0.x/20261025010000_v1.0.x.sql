-- 2026.10.25：新增 Agent 会话与消息明细表，支撑页面会话列表、历史消息与事件重放（SQLite 版）

CREATE TABLE IF NOT EXISTS ai_agent_session (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     TEXT    NOT NULL,
    aid            TEXT    NOT NULL,
    title          TEXT    NOT NULL DEFAULT '新会话',
    status         INTEGER NOT NULL DEFAULT 0,
    is_debug       INTEGER NOT NULL DEFAULT 0,
    pin_order      INTEGER NOT NULL DEFAULT 0,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_agent_session_message (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     INTEGER NOT NULL,
    seq_num        INTEGER NOT NULL,
    role_type      INTEGER NOT NULL,
    status         INTEGER NOT NULL DEFAULT 1,
    started_at     TEXT             DEFAULT NULL,
    finished_at    TEXT             DEFAULT NULL,
    elapsed_ms     INTEGER          DEFAULT NULL,
    content        TEXT             DEFAULT NULL,
    events         TEXT             DEFAULT NULL,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
