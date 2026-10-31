-- 2026.10.27：新增 MCP 插件与 Server 配置表，支持 Agent 绑定 MCP 工具（SQLite 版）
-- 说明：仅保留主键索引，唯一性与查询条件由服务层保证

CREATE TABLE IF NOT EXISTS ai_plugin_mcp (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    description    TEXT             DEFAULT NULL,
    icon           TEXT             DEFAULT NULL,
    status         INTEGER NOT NULL DEFAULT 1,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_plugin_mcp_server (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    plugin_id      INTEGER NOT NULL,
    server_name    TEXT    NOT NULL,
    server_url     TEXT    NOT NULL,
    transport_type INTEGER NOT NULL DEFAULT 1,
    headers        TEXT             DEFAULT NULL,
    status         INTEGER NOT NULL DEFAULT 1,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
