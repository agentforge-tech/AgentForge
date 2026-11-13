package cloud.changlu.agentforge.service.modules.agent.controller;

import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentChatTaskStopVo;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.AgentChatTaskService;
import cloud.changlu.agentforge.service.modules.agent.service.LegacyChatStreamService;
import cloud.changlu.agentforge.service.modules.agent.service.impl.AgentRuntimeConfigResolver;
import cloud.changlu.agentforge.service.modules.agent.support.ChatStopRegistry;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Agent 对话入口：统一承载一次性调用与 legacy 实时流式调用。 */
@RestController
@CrossOrigin
public class AgentChatController {

    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentManagerFactory agentManagerFactory;
    private final LegacyChatStreamService chatStreamService;
    private final AgentChatTaskService chatTaskService;
    private final ChatStopRegistry stopRegistry;

    public AgentChatController(
            AgentRuntimeConfigResolver runtimeConfigResolver,
            AgentManagerFactory agentManagerFactory,
            LegacyChatStreamService chatStreamService,
            AgentChatTaskService chatTaskService,
            ChatStopRegistry stopRegistry) {
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.agentManagerFactory = agentManagerFactory;
        this.chatStreamService = chatStreamService;
        this.chatTaskService = chatTaskService;
        this.stopRegistry = stopRegistry;
    }

    /** 一次性非流式调用，等待 Agent 完成后返回完整结果。 */
    @PostMapping("/api/agent/chat")
    public ChatResult invoke(@RequestBody AgentChatReq req) {
        if (req == null || req.getAid() == null || req.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
        AgentRuntimeConfig config;
        try {
            config = runtimeConfigResolver.resolve(req.getAid(), isDebug);
        } catch (AgentForgeException e) {
            // Agent 未发布、已停止发布或已删除：作为一条正常回答返回，避免 500 与异常堆栈
            return ChatResult.toFinished(req.getMessage().trim(), e.getMessage());
        }
        IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByType(config.getType());
        Object agent =
                agentManager.getAgentAndInitAgent(
                        AgentInitContext.builder()
                                .aid(config.getAid())
                                .agentName(config.getName())
                                .agentConfig(config.getAgentConfig())
                                .debug(isDebug)
                                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                                .build());
        // 一次性非流式调用同样支持停止：按会话登记取消句柄，调用结束后清理。
        String executionId = agentManager.createExecutionId(agent);
        req.setExecutionId(executionId);
        String sessionKey = resolveSessionKey(req);
        stopRegistry.register(
                sessionKey, () -> agentManager.cancel(agent, sessionKey, executionId));
        try {
            return agentManager.ask(agent, req);
        } finally {
            stopRegistry.remove(sessionKey);
        }
    }

    /** legacy 实时流式调用，Agent 执行与当前 SSE 连接保持绑定。 */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest body) {
        return chatStreamService.stream(body, currentUserId());
    }

    /** 停止一段式对话（实时流式或一次性非流式）：先下发取消信号，再等待消息真正收口并返回最终状态。 */
    @PostMapping("/api/chat/stop")
    public AgentChatTaskStopVo stopStream(@RequestParam String sessionId) {
        chatStreamService.stop(sessionId);
        return chatTaskService.stopBySession(sessionId, currentUserId());
    }

    /** 与 StandardReActAgentManager 的 memoryId 默认规则保持一致，保证停止能命中同一会话。 */
    private static String resolveSessionKey(AgentChatReq req) {
        String sessionId = req.getSessionId();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return sessionId;
        }
        boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
        return (isDebug ? "debug-" : "chat-") + req.getAid();
    }

    private Long currentUserId() {
        return 1L;
    }
}
