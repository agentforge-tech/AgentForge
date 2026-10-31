-- 2026.10.29：新增 Agent 链路追踪快照表（SQLite 版）

CREATE TABLE IF NOT EXISTS ai_agent_trace_snapshot (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     TEXT    NOT NULL,
    aid            TEXT    NOT NULL,
    question_round INTEGER NOT NULL,
    step           INTEGER NOT NULL,
    started_at     TEXT             DEFAULT NULL,
    finished_at    TEXT             DEFAULT NULL,
    duration_ms    INTEGER          DEFAULT NULL,
    input_tokens   INTEGER          DEFAULT NULL,
    output_tokens  INTEGER          DEFAULT NULL,
    cached_tokens  INTEGER          DEFAULT NULL,
    total_tokens   INTEGER          DEFAULT NULL,
    finished       INTEGER NOT NULL DEFAULT 0,
    status         TEXT    NOT NULL,
    debug          INTEGER NOT NULL DEFAULT 0,
    snapshot_json  TEXT    NOT NULL,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
