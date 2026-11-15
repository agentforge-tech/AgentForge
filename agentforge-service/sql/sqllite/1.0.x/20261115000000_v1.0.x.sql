-- 2026.11.15：新增 Agent 中断冻结快照表（SQLite）
CREATE TABLE IF NOT EXISTS ai_agent_interrupt_snapshot (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id            TEXT    NOT NULL,
    memory_id           TEXT    NOT NULL,
    source_execution_id TEXT    NOT NULL,
    step                INTEGER NOT NULL,
    status              TEXT    NOT NULL DEFAULT 'PENDING',
    schema_version      INTEGER NOT NULL DEFAULT 1,
    snapshot_json       TEXT    NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,
    claimed_at          DATETIME DEFAULT NULL,
    resolved_at         DATETIME DEFAULT NULL,
    create_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id)
);