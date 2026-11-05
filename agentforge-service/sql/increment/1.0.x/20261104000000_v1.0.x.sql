-- 2026.11.04：新增 Agent 运行中追加消息持久化队列表
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_session_message_append` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '追加消息ID，同时作为对外appendId',
    `session_id`  VARCHAR(64) NOT NULL COMMENT '会话业务标识，同时作为Agent memoryId',
    `content`     LONGTEXT    NOT NULL COMMENT '待插入Agent Loop的用户消息',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1待生效、2已生效、3已延后、4已取消',
    `queued_at`   DATETIME(3) NOT NULL COMMENT '入队时间',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `modify_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent会话运行中追加消息队列';
