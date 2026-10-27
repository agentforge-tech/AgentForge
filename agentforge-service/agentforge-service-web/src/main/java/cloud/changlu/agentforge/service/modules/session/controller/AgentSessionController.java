package cloud.changlu.agentforge.service.modules.session.controller;

import cloud.changlu.agentforge.service.modules.session.domain.req.SessionChangeTitleReq;
import cloud.changlu.agentforge.service.modules.session.domain.req.SessionIdReq;
import cloud.changlu.agentforge.service.modules.session.domain.req.SessionListReq;
import cloud.changlu.agentforge.service.modules.session.domain.req.SessionSetTopReq;
import cloud.changlu.agentforge.service.modules.session.domain.req.SessionTopListReq;
import cloud.changlu.agentforge.service.modules.session.domain.vo.SessionVo;
import cloud.changlu.agentforge.service.modules.session.service.IAgentSessionService;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会话管理 Controller：列表、置顶、改名、删除。 */
@RestController
@RequestMapping("/api/agent/sessions")
@CrossOrigin
public class AgentSessionController {

    private final IAgentSessionService agentSessionService;

    public AgentSessionController(IAgentSessionService agentSessionService) {
        this.agentSessionService = agentSessionService;
    }

    @PostMapping("/sessionList")
    public List<SessionVo> sessionList(@RequestBody(required = false) SessionListReq req) {
        SessionListReq query = req == null ? new SessionListReq() : req;
        return agentSessionService.listNormal(query.getAid(), currentUserId(), query.getLimit());
    }

    @PostMapping("/topList")
    public List<SessionVo> topList(@RequestBody(required = false) SessionTopListReq req) {
        SessionTopListReq query = req == null ? new SessionTopListReq() : req;
        return agentSessionService.listTop(query.getAid(), currentUserId());
    }

    @PostMapping("/setTop")
    public Boolean setTop(@RequestBody SessionSetTopReq req) {
        if (req == null || req.getSessionId() == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return agentSessionService.setTop(
                req.getSessionId(), currentUserId(), Boolean.TRUE.equals(req.getIsTop()));
    }

    @PostMapping("/changeTitle")
    public SessionVo changeTitle(@RequestBody SessionChangeTitleReq req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return agentSessionService.changeTitle(req.getSessionId(), currentUserId(), req.getTitle());
    }

    @PostMapping("/delete")
    public Boolean delete(@RequestBody SessionIdReq req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return agentSessionService.delete(req.getSessionId(), currentUserId());
    }

    /** 当前阶段未接入登录态，统一使用默认用户，后续替换为登录上下文。 */
    private Long currentUserId() {
        return 1L;
    }
}
