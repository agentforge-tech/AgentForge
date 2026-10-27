-- 2026.10.25：新增 Agent 会话与消息明细表，支撑页面会话列表、历史消息与事件重放
-- 默认数据库：agentforge
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_session` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id`     VARCHAR(64)  NOT NULL COMMENT '对外会话标识，同时作为 Agent memoryId',
    `aid`            VARCHAR(64)  NOT NULL COMMENT '关联 ai_agent_config.aid（逻辑外键）',
    `title`          VARCHAR(255) NOT NULL DEFAULT '新会话' COMMENT '会话标题：取首条用户消息前 TITLE_MAX_LENGTH（20）个字符',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '会话状态：0空闲、1运行中、2完成、3失败',
    `is_debug`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否调试会话：0否、1是',
    `pin_order`      INT          NOT NULL DEFAULT 0 COMMENT '置顶序号：0未置顶，正数越大越靠前',
    `create_user_id` BIGINT       NOT NULL DEFAULT 1 COMMENT '创建用户ID',
    `is_deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除、1已删除',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent会话表';

CREATE TABLE IF NOT EXISTS `ai_agent_session_message` (
    `id`             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id`     BIGINT   NOT NULL COMMENT '关联 ai_agent_session.id（逻辑外键）',
    `seq_num`        INT      NOT NULL COMMENT '会话内稳定顺序号，从1开始递增',
    `role_type`      TINYINT  NOT NULL COMMENT '消息角色：1用户、2AI',
    `status`         TINYINT  NOT NULL DEFAULT 1 COMMENT '消息状态：1创建、2处理中、3失败、4取消、5成功',
    `started_at`     DATETIME DEFAULT NULL COMMENT 'AI消息开始处理时间',
    `finished_at`    DATETIME DEFAULT NULL COMMENT '消息完成、失败或取消时间',
    `elapsed_ms`     BIGINT   DEFAULT NULL COMMENT 'AI执行耗时，单位毫秒',
    `content`        LONGTEXT DEFAULT NULL COMMENT '用户问题或AI最终回答文本',
    `events`         LONGTEXT DEFAULT NULL COMMENT '实际推送到页面的SSE事件JSON数组，用于历史重放',
    `create_user_id` BIGINT   NOT NULL DEFAULT 1 COMMENT '创建用户ID',
    `is_deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除、1已删除',
    `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent会话消息明细表';
