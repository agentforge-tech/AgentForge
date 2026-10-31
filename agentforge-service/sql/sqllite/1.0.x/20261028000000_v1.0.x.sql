-- 2026.10.28：新增 HTTP 插件与工具方法/参数表，支持 Agent 绑定 HTTP 工具（SQLite 版）

CREATE TABLE IF NOT EXISTS ai_plugin_http (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    alias          TEXT    NOT NULL,
    base_url       TEXT    NOT NULL,
    static_headers TEXT             DEFAULT NULL,
    description    TEXT             DEFAULT NULL,
    icon           TEXT             DEFAULT NULL,
    status         INTEGER NOT NULL DEFAULT 1,
    is_public      INTEGER NOT NULL DEFAULT 0,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_time    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time     TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_plugin_http_method (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    plugin_id          INTEGER NOT NULL,
    method_name        TEXT    NOT NULL,
    display_name       TEXT             DEFAULT NULL,
    method_description TEXT             DEFAULT NULL,
    http_method_type   INTEGER NOT NULL,
    uri                TEXT    NOT NULL,
    status             INTEGER NOT NULL DEFAULT 1,
    sort_order         INTEGER NOT NULL DEFAULT 0,
    is_deleted         INTEGER NOT NULL DEFAULT 0,
    create_time        TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time        TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_plugin_http_parameter (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    method_id                INTEGER NOT NULL,
    method_param_name        TEXT    NOT NULL,
    method_param_description TEXT             DEFAULT NULL,
    mapped_name              TEXT    NOT NULL,
    use_type_value           INTEGER NOT NULL,
    data_type_value          INTEGER NOT NULL,
    default_value            TEXT             DEFAULT NULL,
    required                 INTEGER NOT NULL DEFAULT 0,
    sort_order               INTEGER NOT NULL DEFAULT 0,
    is_deleted               INTEGER NOT NULL DEFAULT 0,
    create_time              TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modify_time              TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
