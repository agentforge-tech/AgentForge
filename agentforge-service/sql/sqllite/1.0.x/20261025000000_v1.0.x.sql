-- 2026.10.25：新增 Agent 记忆存储表 ai_agent_memrory，支撑 ChatMemory 的模型上下文持久化（SQLite 版）

CREATE TABLE IF NOT EXISTS ai_agent_memrory (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id   TEXT    NOT NULL UNIQUE,
    content     TEXT    NOT NULL,
    create_time TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
