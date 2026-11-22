-- 2026.11.21：新增文件元数据表，支撑附件归属校验、会话引用与后续清理
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_file_info` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_name`        VARCHAR(255) NOT NULL COMMENT '相对存储路径，形如 1/uuid.png',
    `origin_file_name` VARCHAR(255)          DEFAULT NULL COMMENT '上传时原始文件名',
    `visit_url`        VARCHAR(512) NOT NULL COMMENT '可直接访问的完整地址',
    `file_type`        VARCHAR(32)           DEFAULT NULL COMMENT '小写扩展名，不含点',
    `file_size`        BIGINT                DEFAULT NULL COMMENT '字节数',
    `storage_mode`     VARCHAR(32)  NOT NULL DEFAULT 'local' COMMENT '存储模式：local',
    `create_user_id`   BIGINT       NOT NULL DEFAULT 1 COMMENT '上传者',
    `is_deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常，1 已删除',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `modify_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_create_user` (`create_user_id`),
    KEY `idx_visit_url` (`visit_url`(191))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件元数据';

-- 2026.11.21：ai_agent_session_message 新增 req，用于存储用户消息发送时的完整请求快照（含附件元信息）
ALTER TABLE `ai_agent_session_message`
    ADD COLUMN `req` LONGTEXT DEFAULT NULL COMMENT '用户消息请求快照 JSON（含附件）' AFTER `events`;
