-- 2026.10.25：新增 Agent 记忆存储表 ai_agent_memrory，支撑 ChatMemory 的模型上下文持久化
-- 默认数据库：agentforge
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_memrory` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `memory_id`   VARCHAR(255) NOT NULL COMMENT '聊天记忆ID（对应 ChatMemory.id()）',
    `content`     LONGTEXT     NOT NULL COMMENT '聊天内容(JSON格式，ChatMessage 列表序列化结果)',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_memory_id` (`memory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent 会话记忆存储表';
