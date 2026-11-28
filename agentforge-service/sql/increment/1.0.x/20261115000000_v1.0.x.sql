-- 2026.11.15：新增 Agent 中断冻结快照表，支撑 AgentInterruptStore 持久化与批次 CAS
USE `agentforge`;

CREATE TABLE IF NOT EXISTS `ai_agent_interrupt_snapshot` (
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `batch_id`            VARCHAR(80) NOT NULL COMMENT 'Framework 中断批次ID',
    `memory_id`           VARCHAR(128) NOT NULL COMMENT '会话ID，对应 AgentRequest.memoryId',
    `source_execution_id` VARCHAR(64)  NOT NULL COMMENT '产生中断的 Agent 执行ID',
    `step`                INT         NOT NULL COMMENT '产生中断时的 ReAct 步数',
    `status`              VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '批次状态：PENDING/RESUMING/COMPLETED/CANCELLED',
    `schema_version`      INT         NOT NULL DEFAULT 1 COMMENT '快照JSON结构版本',
    `snapshot_json`       LONGTEXT    NOT NULL COMMENT '冻结的工具调用、中断项与拒绝项JSON',
    `version`             BIGINT      NOT NULL DEFAULT 0 COMMENT '状态推进次数，用于审计与后续乐观锁',
    `claimed_at`          DATETIME    DEFAULT NULL COMMENT '批次被抢占为 RESUMING 的时间',
    `resolved_at`         DATETIME    DEFAULT NULL COMMENT '批次完成为 COMPLETED 的时间',
    `create_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modify_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_batch_id` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Agent中断冻结快照表';
