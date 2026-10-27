-- 2026.10.27：新增 Agent 历史版本表与当前发布快照表，隔离编辑态和正式运行态
-- 默认数据库：agentforge
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_config_version` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '版本记录主键ID',
    `version_no`     BIGINT        NOT NULL COMMENT '同一Agent内递增版本号',
    `name`           VARCHAR(255)  NOT NULL COMMENT 'Agent名称快照',
    `type`           INT           NOT NULL COMMENT 'Agent类型：1=ReActAgent',
    `agent_config`   LONGTEXT               DEFAULT NULL COMMENT 'Agent配置JSON快照',
    `exper_config`   LONGTEXT               DEFAULT NULL COMMENT '对话体验配置JSON快照',
    `aid`            VARCHAR(64)   NOT NULL COMMENT 'Agent唯一标识ID',
    `avatar`         VARCHAR(255)           DEFAULT '' COMMENT '头像URL快照',
    `description`    VARCHAR(1024)          DEFAULT '' COMMENT 'Agent描述快照',
    `version_remark` VARCHAR(1024)          DEFAULT '' COMMENT '版本备注：用户补充的版本说明，创建后可再次编辑',
    `is_deleted`     INT           NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `create_user_id` BIGINT        NOT NULL DEFAULT 1 COMMENT '版本提交人ID',
    `create_time`    DATETIME               DEFAULT CURRENT_TIMESTAMP COMMENT '版本生成时间',
    `modify_time`    DATETIME               DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent配置历史版本表';

CREATE TABLE IF NOT EXISTS `ai_agent_config_published` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '发布配置主键ID',
    `name`              VARCHAR(255)  NOT NULL COMMENT '已发布Agent名称',
    `type`              INT           NOT NULL COMMENT 'Agent类型：1=ReActAgent',
    `agent_config`      LONGTEXT               DEFAULT NULL COMMENT '已发布Agent配置JSON',
    `exper_config`      LONGTEXT               DEFAULT NULL COMMENT '已发布对话体验配置JSON',
    `last_publish_time` DATETIME      NOT NULL COMMENT '当前配置最新发布时间',
    `aid`               VARCHAR(64)   NOT NULL COMMENT 'Agent唯一标识ID',
    `avatar`            VARCHAR(255)           DEFAULT '' COMMENT '已发布头像URL',
    `description`       VARCHAR(1024)          DEFAULT '' COMMENT '已发布Agent描述',
    `is_deleted`        INT           NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `create_user_id`    BIGINT        NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `create_time`       DATETIME               DEFAULT CURRENT_TIMESTAMP COMMENT '发布记录创建时间',
    `modify_time`       DATETIME               DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '发布记录更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent当前已发布配置表';

-- 为存量未删除 Agent 幂等生成 V1；版本快照不保存 publish、last_publish_time、is_public。
INSERT INTO `ai_agent_config_version`
    (`version_no`, `name`, `type`, `agent_config`, `exper_config`, `aid`, `avatar`,
     `description`, `is_deleted`, `create_user_id`, `create_time`, `modify_time`)
SELECT
    1, c.`name`, c.`type`, c.`agent_config`, c.`exper_config`, c.`aid`, c.`avatar`,
    c.`description`, 0, c.`create_user_id`, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM `ai_agent_config` c
WHERE c.`is_deleted` = 0
  AND NOT EXISTS (
      SELECT 1
      FROM `ai_agent_config_version` v
      WHERE v.`aid` = c.`aid`
        AND v.`is_deleted` = 0
  );

-- 为存量已发布 Agent 幂等生成当前发布快照；不复制 publish 和 is_public。
INSERT INTO `ai_agent_config_published`
    (`name`, `type`, `agent_config`, `exper_config`, `last_publish_time`, `aid`, `avatar`,
     `description`, `is_deleted`, `create_user_id`, `create_time`, `modify_time`)
SELECT
    c.`name`, c.`type`, c.`agent_config`, c.`exper_config`,
    COALESCE(c.`last_publish_time`, c.`modify_time`, c.`create_time`, CURRENT_TIMESTAMP),
    c.`aid`, c.`avatar`, c.`description`, 0, c.`create_user_id`,
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM `ai_agent_config` c
WHERE c.`is_deleted` = 0
  AND c.`publish` = 1
  AND NOT EXISTS (
      SELECT 1
      FROM `ai_agent_config_published` p
      WHERE p.`aid` = c.`aid`
        AND p.`is_deleted` = 0
  );
