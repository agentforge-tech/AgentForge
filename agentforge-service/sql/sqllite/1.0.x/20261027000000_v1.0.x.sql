-- 2026.10.27：新增 Agent 历史版本表与当前发布快照表，隔离编辑态和正式运行态（SQLite 版）

CREATE TABLE IF NOT EXISTS ai_agent_config_version (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    version_no     INTEGER NOT NULL,
    name           TEXT    NOT NULL,
    type           INTEGER NOT NULL,
    agent_config   TEXT             DEFAULT NULL,
    exper_config   TEXT             DEFAULT NULL,
    aid            TEXT    NOT NULL,
    avatar         TEXT             DEFAULT '',
    description    TEXT             DEFAULT '',
    version_remark TEXT             DEFAULT '',
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    create_user_id INTEGER NOT NULL DEFAULT 1,
    create_time    TEXT             DEFAULT CURRENT_TIMESTAMP,
    modify_time    TEXT             DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_agent_config_published (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT    NOT NULL,
    type              INTEGER NOT NULL,
    agent_config      TEXT             DEFAULT NULL,
    exper_config      TEXT             DEFAULT NULL,
    last_publish_time TEXT    NOT NULL,
    aid               TEXT    NOT NULL,
    avatar            TEXT             DEFAULT '',
    description       TEXT             DEFAULT '',
    is_deleted        INTEGER NOT NULL DEFAULT 0,
    create_user_id    INTEGER NOT NULL DEFAULT 1,
    create_time       TEXT             DEFAULT CURRENT_TIMESTAMP,
    modify_time       TEXT             DEFAULT CURRENT_TIMESTAMP
);

-- 为存量未删除 Agent 幂等生成 V1；版本快照不保存 publish、last_publish_time、is_public。
INSERT INTO ai_agent_config_version
    (version_no, name, type, agent_config, exper_config, aid, avatar,
     description, is_deleted, create_user_id, create_time, modify_time)
SELECT
    1, c.name, c.type, c.agent_config, c.exper_config, c.aid, c.avatar,
    c.description, 0, c.create_user_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM ai_agent_config c
WHERE c.is_deleted = 0
  AND NOT EXISTS (
      SELECT 1
      FROM ai_agent_config_version v
      WHERE v.aid = c.aid
        AND v.is_deleted = 0
  );

-- 为存量已发布 Agent 幂等生成当前发布快照；不复制 publish 和 is_public。
INSERT INTO ai_agent_config_published
    (name, type, agent_config, exper_config, last_publish_time, aid, avatar,
     description, is_deleted, create_user_id, create_time, modify_time)
SELECT
    c.name, c.type, c.agent_config, c.exper_config,
    COALESCE(c.last_publish_time, c.modify_time, c.create_time, CURRENT_TIMESTAMP),
    c.aid, c.avatar, c.description, 0, c.create_user_id,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM ai_agent_config c
WHERE c.is_deleted = 0
  AND c.publish = 1
  AND NOT EXISTS (
      SELECT 1
      FROM ai_agent_config_published p
      WHERE p.aid = c.aid
        AND p.is_deleted = 0
  );
