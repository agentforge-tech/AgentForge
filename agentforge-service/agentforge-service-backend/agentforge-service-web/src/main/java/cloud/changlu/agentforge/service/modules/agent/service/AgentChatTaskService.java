package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentInterruptResumeReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentChatTaskStopVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentInterruptResumeVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.ChatTaskSubmitVo;

/** 二段式对话任务提交与运行控制。 */
public interface AgentChatTaskService {
    ChatTaskSubmitVo submit(ChatStreamRequest request, Long userId);

    /** 停止指定 AI 消息对应的一轮回答，返回数据库中的最终状态。 */
    AgentChatTaskStopVo stop(Long messageId, Long userId);

    /** 一段式按会话停止：解析该会话当前运行中的 AI 消息并等待其收口，返回最终状态。 */
    AgentChatTaskStopVo stopBySession(String sessionId, Long userId);

    /** 一次性提交 HITL 批次的全部审批/回答。 */
    AgentInterruptResumeVo resumeInterrupt(AgentInterruptResumeReq request, Long userId);
}
