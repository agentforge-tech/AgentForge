-- 2026.11.06：新增可重连流式对话事件暂存表（MySQL Stream 适配器）
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_session_message_event` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `message_id`  BIGINT      NOT NULL COMMENT 'AI消息ID，同时作为一轮Run ID',
    `event_seq`   BIGINT      NOT NULL COMMENT '消息内严格递增事件序号，从1开始',
    `event_type`  VARCHAR(32) NOT NULL COMMENT '事件大类，如resp/tool_call',
    `payload`     LONGTEXT    NOT NULL COMMENT 'StreamEventResponse完整JSON',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_seq` (`message_id`, `event_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent消息流式事件暂存表';
