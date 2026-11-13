package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 旧版单请求 SSE 对话链路，保留用于灰度回退。 */
public interface LegacyChatStreamService {
    SseEmitter stream(ChatStreamRequest request, Long userId);

    void stop(String sessionId);
}
