package cloud.changlu.agentforge.service.modules.session.controller;

import cloud.changlu.agentforge.service.modules.session.domain.req.SessionIdReq;
import cloud.changlu.agentforge.service.modules.session.domain.vo.SessionMessageVo;
import cloud.changlu.agentforge.service.modules.session.service.IAgentSessionService;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话消息 Controller：历史消息与事件重放。 */
@RestController
@RequestMapping("/api/agent/session-messages")
@CrossOrigin
public class AgentSessionMessageController {

    private final IAgentSessionService agentSessionService;

    public AgentSessionMessageController(IAgentSessionService agentSessionService) {
        this.agentSessionService = agentSessionService;
    }

    @PostMapping("/detail")
    public List<SessionMessageVo> detail(@RequestBody SessionIdReq req) {
        if (req == null || req.getSessionId() == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return agentSessionService.listMessages(req.getSessionId(), currentUserId());
    }

    /** 当前阶段未接入登录态，统一使用默认用户。 */
    private Long currentUserId() {
        return 1L;
    }
}
