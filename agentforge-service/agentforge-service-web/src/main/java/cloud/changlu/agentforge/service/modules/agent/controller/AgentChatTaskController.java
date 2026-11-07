package cloud.changlu.agentforge.service.modules.agent.controller;

import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentChatTaskStopVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.ChatTaskSubmitVo;
import cloud.changlu.agentforge.service.modules.agent.service.AgentChatTaskService;
import cloud.changlu.agentforge.service.modules.agent.service.impl.ReconnectableChatStreamService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 可重连流式对话二段式接口：提交任务与订阅事件完全解耦。 */
@RestController
@RequestMapping("/api/chat/tasks")
@CrossOrigin
public class AgentChatTaskController {
    private final AgentChatTaskService chatTaskService;
    private final ReconnectableChatStreamService streamService;

    public AgentChatTaskController(
            AgentChatTaskService chatTaskService, ReconnectableChatStreamService streamService) {
        this.chatTaskService = chatTaskService;
        this.streamService = streamService;
    }

    @PostMapping("/submit")
    public ChatTaskSubmitVo submit(@RequestBody ChatStreamRequest body) {
        return chatTaskService.submit(body, currentUserId());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam Long messageId,
            @RequestParam(defaultValue = "0") Long afterSeq,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return streamService.subscribe(
                messageId, resolveCursor(afterSeq, lastEventId), currentUserId());
    }

    @PostMapping("/stop")
    public AgentChatTaskStopVo stop(@RequestParam Long messageId) {
        return chatTaskService.stop(messageId, currentUserId());
    }

    private static long resolveCursor(Long afterSeq, String lastEventId) {
        long cursor = afterSeq == null ? 0L : Math.max(0L, afterSeq);
        if (cursor != 0L || lastEventId == null || lastEventId.trim().isEmpty()) return cursor;
        try {
            return Math.max(0L, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Long currentUserId() {
        return 1L;
    }
}
