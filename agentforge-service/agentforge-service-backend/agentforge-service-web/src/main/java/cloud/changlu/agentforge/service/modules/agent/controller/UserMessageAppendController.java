package cloud.changlu.agentforge.service.modules.agent.controller;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;
import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** 当前 Run 的用户消息追加与取消入口。 */
@RestController
@RequestMapping("/api/chat/append")
public class UserMessageAppendController {
    private final AgentSessionMapper sessionMapper;
    private final IUserMessageAppendService appendService;
    private final UserMessageAppendEventListener eventListener;

    public UserMessageAppendController(
            AgentSessionMapper sessionMapper,
            IUserMessageAppendService appendService,
            UserMessageAppendEventListener eventListener) {
        this.sessionMapper = sessionMapper;
        this.appendService = appendService;
        this.eventListener = eventListener;
    }

    @PostMapping
    public AppendResponse append(@RequestBody AppendRequest request) {
        if (!validSession(request)) return AppendResponse.rejected("SESSION_NOT_FOUND");
        UserMessageAppendResult result =
                appendService.append(request.getSessionId(), request.getMessage());
        if (!result.isQueued()) return AppendResponse.rejected(result.getCode().name());
        eventListener.queued(
                request.getSessionId(), result.getMessage(), result.getQueuePosition());
        return AppendResponse.queued(result.getMessage().getAppendId(), result.getQueuePosition());
    }

    @PostMapping("/cancel")
    public CancelResponse cancel(@RequestBody CancelRequest request) {
        if (!validSession(request) || request.getAppendId() == null) {
            return new CancelResponse(false, "SESSION_NOT_FOUND");
        }
        Optional<AppendedUserMessage> removed =
                appendService.cancel(request.getSessionId(), request.getAppendId());
        if (!removed.isPresent()) return new CancelResponse(false, "ALREADY_EFFECTIVE_OR_TERMINAL");
        eventListener.canceled(request.getSessionId(), removed.get());
        return new CancelResponse(true, null);
    }

    private boolean validSession(BaseRequest request) {
        if (request == null || request.getSessionId() == null || request.getAid() == null)
            return false;
        AgentSessionPojo session = sessionMapper.selectBySessionId(request.getSessionId());
        return session != null
                && request.getAid().equals(session.getAid())
                && (Boolean.TRUE.equals(request.getIsDebug()) ? 1 : 0) == session.getIsDebug()
                && currentUserId() == session.getCreateUserId();
    }

    private long currentUserId() {
        return 1L;
    }

    public static class BaseRequest {
        private String aid;
        private String sessionId;
        private Boolean isDebug;

        public String getAid() {
            return aid;
        }

        public void setAid(String aid) {
            this.aid = aid;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public Boolean getIsDebug() {
            return isDebug;
        }

        public void setIsDebug(Boolean debug) {
            isDebug = debug;
        }
    }

    public static final class AppendRequest extends BaseRequest {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static final class CancelRequest extends BaseRequest {
        private Long appendId;

        public Long getAppendId() {
            return appendId;
        }

        public void setAppendId(Long appendId) {
            this.appendId = appendId;
        }
    }

    public static final class AppendResponse {
        private final boolean queued;
        private final Long appendId;
        private final Integer queuePosition;
        private final String reason;

        private AppendResponse(
                boolean queued, Long appendId, Integer queuePosition, String reason) {
            this.queued = queued;
            this.appendId = appendId;
            this.queuePosition = queuePosition;
            this.reason = reason;
        }

        static AppendResponse queued(Long id, int position) {
            return new AppendResponse(true, id, position, null);
        }

        static AppendResponse rejected(String reason) {
            return new AppendResponse(false, null, null, reason);
        }

        public boolean isQueued() {
            return queued;
        }

        public Long getAppendId() {
            return appendId;
        }

        public Integer getQueuePosition() {
            return queuePosition;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class CancelResponse {
        private final boolean canceled;
        private final String reason;

        CancelResponse(boolean canceled, String reason) {
            this.canceled = canceled;
            this.reason = reason;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public String getReason() {
            return reason;
        }
    }
}
