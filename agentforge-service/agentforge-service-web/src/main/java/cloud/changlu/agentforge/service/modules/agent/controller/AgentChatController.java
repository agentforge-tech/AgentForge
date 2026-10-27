package cloud.changlu.agentforge.service.modules.agent.controller;

import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentChatReq;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.AgentRuntimeConfigResolver;

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

    private final AgentRuntimeConfigResolver runtimeConfigResolver;
    private final AgentManagerFactory agentManagerFactory;

    public AgentChatController(
            AgentRuntimeConfigResolver runtimeConfigResolver,
            AgentManagerFactory agentManagerFactory) {
        this.runtimeConfigResolver = runtimeConfigResolver;
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
        boolean isDebug = Boolean.TRUE.equals(req.getIsDebug());
        AgentRuntimeConfig config;
        try {
            config = runtimeConfigResolver.resolve(req.getAid(), isDebug);
        } catch (AgentForgeException e) {
            // Agent 未发布、已停止发布或已删除：作为一条正常回答返回，避免 500 与异常堆栈
            return ChatResult.toFinished(req.getMessage().trim(), e.getMessage());
        }
        IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByType(config.getType());
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
