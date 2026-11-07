-- 2026.11.07：ai_agent_session_message 新增 execution_id，用于手动停止精确定位 Agent Run
USE `agentforge`;

ALTER TABLE `ai_agent_session_message`
    ADD COLUMN `execution_id` VARCHAR(64) DEFAULT NULL COMMENT 'Framework 单次执行标识，用于停止时精确定位 Agent Run' AFTER `status`;
