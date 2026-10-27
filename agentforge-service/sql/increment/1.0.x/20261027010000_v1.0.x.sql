-- 2026.10.27：新增 MCP 插件与 Server 配置表，支持 Agent 绑定 MCP 工具
-- 默认数据库：agentforge
-- 说明：仅保留主键索引，唯一性与查询条件由服务层保证
USE `agentforge`;

-- ============================================
-- 1. MCP 插件表
-- 一个插件可以包含多个 MCP Server
-- ============================================
CREATE TABLE IF NOT EXISTS `ai_plugin_mcp` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`           VARCHAR(128) NOT NULL COMMENT '插件名称',
    `description`    VARCHAR(1024)         DEFAULT NULL COMMENT '插件描述',
    `icon`           VARCHAR(512)          DEFAULT NULL COMMENT '插件图标',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `create_user_id` BIGINT       NOT NULL DEFAULT 1 COMMENT '上传人',
    `is_deleted`     INT          NOT NULL DEFAULT 0 COMMENT '是否逻辑删除 0未删除、1已删除',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP插件表';

-- ============================================
-- 2. MCP Server 表
-- 一个 MCP 插件可以关联多个 Server
-- ============================================
CREATE TABLE IF NOT EXISTS `ai_plugin_mcp_server` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `plugin_id`      BIGINT        NOT NULL COMMENT '所属MCP插件ID',
    `server_name`    VARCHAR(128)  NOT NULL COMMENT 'Server名称',
    `server_url`     VARCHAR(2048) NOT NULL COMMENT 'MCP服务完整URL',
    `transport_type` TINYINT     NOT NULL DEFAULT 1
        COMMENT '传输协议：1streamable_http 2sse',
    `headers`        TEXT                   DEFAULT NULL COMMENT 'HTTP请求头配置（敏感字段加密存储）',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `create_user_id` BIGINT        NOT NULL DEFAULT 1 COMMENT '上传人',
    `is_deleted`     INT           NOT NULL DEFAULT 0 COMMENT '是否逻辑删除 0未删除、1已删除',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP插件Server配置表';
