package cloud.changlu.agentforge.service.modules.agent.controller;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVo;
import cloud.changlu.agentforge.service.modules.agent.service.IAgentConfigService;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Agent 配置 Controller。 */
@RestController
@RequestMapping("/api/agent-config")
@CrossOrigin
public class AgentConfigController {

    private final IAgentConfigService agentConfigService;

    public AgentConfigController(IAgentConfigService agentConfigService) {
        this.agentConfigService = agentConfigService;
    }

    @GetMapping("/page")
    public PageResult<List<AgentConfigVo>> page(AgentConfigQueryReq req) {
        return agentConfigService.page(req);
    }

    @GetMapping("/{id}")
    public AgentConfigVo getInfo(@PathVariable("id") Long id) {
        return agentConfigService.getById(id);
    }

    @PostMapping("/upsert")
    public AgentConfigVo upsert(@RequestBody AgentConfigUpsertReq req) {
        return agentConfigService.upsert(req);
    }

    @PostMapping("/{id}/publish")
    public AgentConfigVo publish(@PathVariable("id") Long id) {
        return agentConfigService.updatePublishStatus(id, true);
    }

    @PostMapping("/{id}/unpublish")
    public AgentConfigVo unpublish(@PathVariable("id") Long id) {
        return agentConfigService.updatePublishStatus(id, false);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        agentConfigService.delete(id);
    }
}
