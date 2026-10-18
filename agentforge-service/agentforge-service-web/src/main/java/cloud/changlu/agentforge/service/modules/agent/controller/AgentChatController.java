package cloud.changlu.agentforge.service.modules.agent.controller;

import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 统一对话接口，isDebug 仅决定是否使用调试运行时。 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin
public class AgentChatController {

    private final AgentConfigMapper agentConfigMapper;
    private final AgentManagerFactory agentManagerFactory;

    public AgentChatController(
            AgentConfigMapper agentConfigMapper, AgentManagerFactory agentManagerFactory) {
        this.agentConfigMapper = agentConfigMapper;
        this.agentManagerFactory = agentManagerFactory;
    }

    @PostMapping("/chat")
    public ChatResult chat(@RequestBody AgentChatReq req) {
        if (req == null || req.getAid() == null || req.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (req.getMessage() == null || req.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        AgentConfigPojo config = agentConfigMapper.selectByAid(req.getAid().trim());
        if (config == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除");
        }
        boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
        IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByTypeAndMode(config.getType(), req.getMode());
        Object agent =
                agentManager.getAgentAndInitAgent(
                        AgentInitContext.builder()
                                .aid(config.getAid())
                                .agentConfig(config.getAgentConfig())
                                .debug(isDebug)
                                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                                .build());
        return agentManager.ask(agent, req);
    }
}
