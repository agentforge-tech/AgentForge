-- 2026.10.14：新增 LLM 模型配置表 ai_model_config，支撑「我的模型」的增删改查与连通性/Function Call 测试
-- 默认数据库：agentforge
CREATE DATABASE IF NOT EXISTS `agentforge`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_model_config` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(255) NOT NULL COMMENT '自定义命名',
    `provider` INT NOT NULL COMMENT '模型厂商（OpenAI-1，Anthropic-2）',
    `model_type` INT NOT NULL COMMENT '模型类别（chat-0、embedding-1）',
    `modelname` VARCHAR(255) NOT NULL COMMENT '模型名称',
    `url` VARCHAR(500) DEFAULT NULL COMMENT '模型连接地址（baseUrl）',
    `api_key` VARCHAR(500) DEFAULT NULL COMMENT '模型密钥',
    `create_type` INT NOT NULL DEFAULT 1 COMMENT '创建类型（系统内置-0、用户创建-1）',
    `create_user_id` BIGINT NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标志（0-未删除，1-已删除）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='LLM模型配置表';



CREATE TABLE IF NOT EXISTS `ai_agent_config` (
                                                 `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
                                                 `name` VARCHAR(255) NOT NULL COMMENT 'agent名称',
    `type` INT NOT NULL COMMENT 'agent类型 1=ReActAgent',
    `agent_config` LONGTEXT DEFAULT NULL COMMENT 'agent配置项(JSON)',
    `exper_config` LONGTEXT DEFAULT NULL COMMENT '对话体验面板配置(JSON)',
    `publish` INT NOT NULL DEFAULT 0 COMMENT '是否发布 0暂未发布、1已发布',
    `last_publish_time` DATETIME DEFAULT NULL COMMENT '最新发布时间',
    `aid` VARCHAR(64) NOT NULL COMMENT 'agent唯一标识ID',
    `is_public` INT NOT NULL DEFAULT 0 COMMENT '是否公开 0不公开、1公开',
    `avatar` VARCHAR(255) DEFAULT '' COMMENT '头像URL',
    `description` VARCHAR(1024) DEFAULT '' COMMENT 'Agent描述，用于在广场中展示',
    `is_deleted` INT NOT NULL DEFAULT 0 COMMENT '是否逻辑删除 0未删除、1已删除',
    `create_user_id` BIGINT NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_aid` (`aid`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用agent参数配置表';