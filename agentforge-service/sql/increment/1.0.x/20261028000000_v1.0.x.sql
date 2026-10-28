USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_plugin_http` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(128) NOT NULL COMMENT '插件名称',
    `alias` VARCHAR(64) NOT NULL COMMENT '工具名前缀',
    `base_url` VARCHAR(1024) NOT NULL COMMENT '服务基础地址',
    `static_headers` TEXT DEFAULT NULL COMMENT '静态请求头JSON',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '插件描述',
    `icon` VARCHAR(512) DEFAULT NULL COMMENT '插件图标',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `is_public` TINYINT NOT NULL DEFAULT 0 COMMENT '是否公开：0私有 1公开',
    `create_user_id` BIGINT NOT NULL DEFAULT 1 COMMENT '创建者ID',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件表';

CREATE TABLE IF NOT EXISTS `ai_plugin_http_method` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `plugin_id` BIGINT NOT NULL COMMENT '所属插件ID',
    `method_name` VARCHAR(64) NOT NULL COMMENT '模型可见工具名后缀',
    `display_name` VARCHAR(128) DEFAULT NULL COMMENT '展示名称',
    `method_description` VARCHAR(1024) DEFAULT NULL COMMENT '工具描述',
    `http_method_type` TINYINT NOT NULL COMMENT 'HTTP方法：1GET 2POST 3PUT 4DELETE 5PATCH',
    `uri` VARCHAR(1024) NOT NULL COMMENT '请求URI',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件方法表';

CREATE TABLE IF NOT EXISTS `ai_plugin_http_parameter` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `method_id` BIGINT NOT NULL COMMENT '所属方法ID',
    `method_param_name` VARCHAR(64) NOT NULL COMMENT '模型侧参数名',
    `method_param_description` VARCHAR(512) DEFAULT NULL COMMENT '参数描述',
    `mapped_name` VARCHAR(128) NOT NULL COMMENT '真实API参数名',
    `use_type_value` TINYINT NOT NULL COMMENT '位置：1Query 2Body 3Path 4Header',
    `data_type_value` TINYINT NOT NULL COMMENT '类型：1String 2Integer 3Number 4Boolean 5Array 6Object',
    `default_value` VARCHAR(2048) DEFAULT NULL COMMENT '默认值',
    `required` TINYINT NOT NULL DEFAULT 0 COMMENT '是否必填：0否 1是',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HTTP插件方法参数表';
