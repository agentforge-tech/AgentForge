-- 2026.11.07：ai_agent_session_message 新增 execution_id，用于手动停止精确定位 Agent Run
ALTER TABLE ai_agent_session_message ADD COLUMN execution_id TEXT DEFAULT NULL;
