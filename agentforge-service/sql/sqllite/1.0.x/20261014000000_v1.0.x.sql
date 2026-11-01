-- 2026.10.14：新增 LLM 模型配置表 ai_model_config 与通用 Agent 配置表 ai_agent_config（SQLite 版）
-- 说明：SQLite 无数据库/字符集概念，时间字段统一使用 TEXT + CURRENT_TIMESTAMP
-- 说明：SQLite 一个 .db 文件即一个库，无 CREATE DATABASE/USE 语句；
--       库文件由 upgrate-sqlite.sh 打开时自动创建（先 mkdir 目录再 sqlite3 打开），故此处无需建库语句。

CREATE TABLE IF NOT EXISTS ai_model_config (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    provider        INTEGER NOT NULL,
    model_type      INTEGER NOT NULL,
    modelname       TEXT    NOT NULL,
    url             TEXT             DEFAULT NULL,
    api_key         TEXT             DEFAULT NULL,
    create_type     INTEGER NOT NULL DEFAULT 1,
    create_user_id  INTEGER NOT NULL DEFAULT 1,
    is_deleted      INTEGER          DEFAULT 0,
    create_time     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_agent_config (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT    NOT NULL,
    type              INTEGER NOT NULL,
    agent_config      TEXT             DEFAULT NULL,
    exper_config      TEXT             DEFAULT NULL,
    publish           INTEGER NOT NULL DEFAULT 0,
    last_publish_time TEXT             DEFAULT NULL,
    aid               TEXT    NOT NULL UNIQUE,
    is_public         INTEGER NOT NULL DEFAULT 0,
    avatar            TEXT             DEFAULT '',
    description       TEXT             DEFAULT '',
    is_deleted        INTEGER NOT NULL DEFAULT 0,
    create_user_id    INTEGER NOT NULL DEFAULT 1,
    create_time       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
