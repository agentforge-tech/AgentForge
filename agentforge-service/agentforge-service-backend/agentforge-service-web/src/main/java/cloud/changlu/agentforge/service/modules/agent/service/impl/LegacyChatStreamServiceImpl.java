package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.modules.agent.attachment.AgentChatAttachmentService;
import cloud.changlu.agentforge.service.modules.agent.attachment.AgentModelCapability;
import cloud.changlu.agentforge.service.modules.agent.attachment.AgentModelCapabilityResolver;
import cloud.changlu.agentforge.service.modules.agent.attachment.ResolvedChatAttachment;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.service.modules.agent.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.LegacyChatStreamService;
import cloud.changlu.agentforge.service.modules.agent.support.ChatStopRegistry;
import cloud.changlu.agentforge.service.modules.session.service.IAgentSessionService;
import cloud.changlu.agentforge.service.protocol.processor.AiResponseProcessor;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class LegacyChatStreamServiceImpl implements LegacyChatStreamService {
    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentManagerFactory agentManagerFactory;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware lifecycleMiddleware;
    private final IAgentSessionService sessionService;
    private final ChatStopRegistry stopRegistry;
    private final AgentChatAttachmentService attachmentService;
    private final AgentModelCapabilityResolver modelCapabilityResolver;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public LegacyChatStreamServiceImpl(
            AgentRuntimeConfigResolver runtimeConfigResolver,
            AgentManagerFactory agentManagerFactory,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware lifecycleMiddleware,
            IAgentSessionService sessionService,
            ChatStopRegistry stopRegistry,
            AgentChatAttachmentService attachmentService,
            AgentModelCapabilityResolver modelCapabilityResolver) {
        this.runtimeConfigResolver = runtimeConfigResolver;
        this.agentManagerFactory = agentManagerFactory;
        this.streamingMiddleware = streamingMiddleware;
        this.lifecycleMiddleware = lifecycleMiddleware;
        this.sessionService = sessionService;
        this.stopRegistry = stopRegistry;
        this.attachmentService = attachmentService;
        this.modelCapabilityResolver = modelCapabilityResolver;
    }

    @Override
    public SseEmitter stream(ChatStreamRequest body, Long userId) {
        validate(body);
        boolean debug = Boolean.TRUE.equals(body.getIsDebug());
        AgentRuntimeConfig config;
        try {
            config = runtimeConfigResolver.resolve(body.getAid(), debug);
        } catch (AgentForgeException e) {
            return notice(e.getMessage());
        }
        // 附件校验先于会话初始化：避免「会话已建、AI 消息已插入」后因附件非法整轮失败。
        AgentModelCapability capability = modelCapabilityResolver.resolve(config);
        java.util.List<Long> fileIds = body.resolveFileIds();
        java.util.List<ResolvedChatAttachment> resolved =
                attachmentService.resolve(fileIds, userId);
        java.util.List<AgentAttachment> attachments =
                attachmentService.toAgentAttachments(
                        resolved,
                        capability == null ? null : capability.getModelConfig(),
                        capability == null ? null : capability.getProviderCode());
        String reqJson = attachmentService.buildUserReqJson(body, resolved);
        IAgentSessionService.SessionInitResult init =
                sessionService.initChat(
                        config.getAid(),
                        body.getSessionId(),
                        safeMessage(body),
                        reqJson,
                        userId,
                        debug);
        IAgentManager<Object, Object> manager =
                agentManagerFactory.getAgentManagerByType(config.getType());
        Object agent =
                manager.getAgentAndInitAgent(
                        AgentInitContext.builder()
                                .aid(config.getAid())
                                .agentName(config.getName())
                                .agentConfig(config.getAgentConfig())
                                .debug(debug)
                                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                                .build());
        String sessionId = init.getSessionId();
        SseEmitter emitter = new SseEmitter(0L);
        streamingMiddleware.bind(sessionId, emitter);
        AgentChatReq request = buildRequest(config, body, init, debug, userId);
        request.setAttachments(attachments);
        request.setExecutionId(manager.createExecutionId(agent));
        stopRegistry.register(
                sessionId, () -> manager.cancel(agent, sessionId, request.getExecutionId()));
        emitter.onCompletion(() -> streamingMiddleware.unbind(sessionId));
        emitter.onTimeout(() -> streamingMiddleware.unbind(sessionId));
        executor.execute(() -> start(manager, agent, request));
        return emitter;
    }

    @Override
    public void stop(String sessionId) {
        stopRegistry.cancel(sessionId);
        streamingMiddleware.unbind(sessionId);
    }

    private void start(IAgentManager<Object, Object> manager, Object agent, AgentChatReq request) {
        try {
            manager.stream(agent, request)
                    .onCompleteResponse(response -> stopRegistry.remove(request.getSessionId()))
                    .onError(error -> stopRegistry.remove(request.getSessionId()))
                    .ignoreErrors()
                    .start();
        } catch (RuntimeException e) {
            stopRegistry.remove(request.getSessionId());
            lifecycleMiddleware.finishWithError(request.getSessionId(), e);
            streamingMiddleware.onError(request.getSessionId(), e);
        }
    }

    private static AgentChatReq buildRequest(
            AgentRuntimeConfig config,
            ChatStreamRequest body,
            IAgentSessionService.SessionInitResult init,
            boolean debug,
            Long userId) {
        AgentChatReq request = new AgentChatReq();
        request.setAid(config.getAid());
        request.setSessionId(init.getSessionId());
        request.setMessage(safeMessage(body));
        request.setIsDebug(debug);
        request.setUserId(userId);
        request.setAiMessageId(init.getAiMessageId());
        request.setSessionTitle(init.getTitle());
        return request;
    }

    private static SseEmitter notice(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().data(AiResponseProcessor.respText(message, false)));
            emitter.send(SseEmitter.event().data(AiResponseProcessor.respEnd(true)));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开。
        }
        emitter.complete();
        return emitter;
    }

    private static String safeMessage(ChatStreamRequest body) {
        return body == null || body.getMessage() == null ? "" : body.getMessage().trim();
    }

    private static void validate(ChatStreamRequest body) {
        if (body == null || body.getAid() == null || body.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        java.util.List<Long> fileIds = body.resolveFileIds();
        boolean hasMessage = body.getMessage() != null && !body.getMessage().trim().isEmpty();
        boolean hasAttachments = fileIds != null && !fileIds.isEmpty();
        if (!hasMessage && !hasAttachments) {
            throw new IllegalArgumentException("对话内容或附件不能同时为空");
        }
    }
}
