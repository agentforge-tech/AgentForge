package cloud.changlu.agentforge.service.web;

import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.stream.ServiceStreamingMiddleware;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    private final AgentConfigMapper agentConfigMapper;
    private final AgentManagerFactory agentManagerFactory;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Runnable> activeStops = new ConcurrentHashMap<String, Runnable>();

    public ChatController(
            AgentConfigMapper agentConfigMapper,
            AgentManagerFactory agentManagerFactory,
            ServiceStreamingMiddleware streamingMiddleware) {
        this.agentConfigMapper = agentConfigMapper;
        this.agentManagerFactory = agentManagerFactory;
        this.streamingMiddleware = streamingMiddleware;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest body) {
        if (body == null || body.getAid() == null || body.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        AgentConfigPojo config = agentConfigMapper.selectByAid(body.getAid().trim());
        if (config == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除");
        }
        final boolean isDebug = Boolean.TRUE.equals(body.getIsDebug());
        if (!isDebug && !Integer.valueOf(1).equals(config.getPublish())) {
            throw new IllegalArgumentException("Agent 尚未发布");
        }
        final IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByType(config.getType());
        final Object agent =
                agentManager.getAgentAndInitAgent(
                        AgentInitContext.builder()
                                .aid(config.getAid())
                                .agentConfig(config.getAgentConfig())
                                .debug(isDebug)
                                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                                .build());
        final String sessionId =
                body.getSessionId() == null || body.getSessionId().trim().isEmpty()
                        ? UUID.randomUUID().toString()
                        : body.getSessionId().trim();
        final SseEmitter emitter = new SseEmitter(0L);
        streamingMiddleware.bind(sessionId, emitter);
        activeStops.put(sessionId, () -> agentManager.cancel(agent, sessionId));
        emitter.onCompletion(() -> cleanup(sessionId));
        emitter.onTimeout(() -> cleanup(sessionId));

        final AgentChatReq chatReq = new AgentChatReq();
        chatReq.setAid(config.getAid());
        chatReq.setSessionId(sessionId);
        chatReq.setMessage(body.getMessage());
        chatReq.setIsDebug(isDebug);

        executor.execute(
                () -> {
                    try {
                        // 事件全部由 ServiceStreamingMiddleware 在 Agent 生命周期回调中发送
                        agentManager.stream(agent, chatReq).ignoreErrors().start();
                    } catch (RuntimeException e) {
                        streamingMiddleware.onError(sessionId, e);
                    }
                });
        return emitter;
    }

    @PostMapping("/stop")
    public void stop(@RequestParam String sessionId) {
        Runnable stop = activeStops.remove(sessionId);
        if (stop != null) {
            stop.run();
        }
        streamingMiddleware.unbind(sessionId);
    }

    private void cleanup(String sessionId) {
        activeStops.remove(sessionId);
        streamingMiddleware.unbind(sessionId);
    }
}
