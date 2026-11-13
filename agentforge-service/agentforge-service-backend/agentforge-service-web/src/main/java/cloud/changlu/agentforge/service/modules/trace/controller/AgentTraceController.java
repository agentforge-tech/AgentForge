package cloud.changlu.agentforge.service.modules.trace.controller;

import cloud.changlu.agentforge.service.modules.trace.domain.AgentTraceSnapshotPojo;
import cloud.changlu.agentforge.service.modules.trace.service.AgentTraceQueryService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-trace")
public class AgentTraceController {

    private final AgentTraceQueryService queryService;

    public AgentTraceController(AgentTraceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/sessions")
    public List<AgentTraceSnapshotPojo> sessions(
            @RequestParam(value = "aid", required = false) String aid,
            @RequestParam(value = "debug", required = false) Boolean debug,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queryService.getRecentSessions(aid, debug == null ? null : (debug ? 1 : 0), limit);
    }

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> session(@PathVariable("sessionId") String sessionId) {
        return queryService.getSessionTrace(sessionId);
    }
}
