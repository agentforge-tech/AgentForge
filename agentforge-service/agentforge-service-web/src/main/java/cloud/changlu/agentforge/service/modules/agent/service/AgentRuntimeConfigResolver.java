package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigMapper;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigPublishedMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPublishedPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.exception.AgentUnavailableException;

import org.springframework.stereotype.Service;

/** 根据运行态从当前配置表或发布快照表解析 Agent 配置。 */
@Service
public class AgentRuntimeConfigResolver {

    private final AgentConfigMapper agentConfigMapper;
    private final AgentConfigPublishedMapper agentConfigPublishedMapper;

    public AgentRuntimeConfigResolver(
            AgentConfigMapper agentConfigMapper,
            AgentConfigPublishedMapper agentConfigPublishedMapper) {
        this.agentConfigMapper = agentConfigMapper;
        this.agentConfigPublishedMapper = agentConfigPublishedMapper;
    }

    public AgentRuntimeConfig resolve(String aid, boolean debug) {
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        String normalizedAid = aid.trim();
        if (debug) {
            AgentConfigPojo config = agentConfigMapper.selectByAid(normalizedAid);
            if (config == null) {
                throw new AgentUnavailableException("该 Agent 不存在或已被删除，暂时无法对话。");
            }
            return new AgentRuntimeConfig(
                    config.getAid(), config.getType(), config.getAgentConfig());
        }
        AgentConfigPublishedPojo published = agentConfigPublishedMapper.selectByAid(normalizedAid);
        if (published == null) {
            throw new AgentUnavailableException("该 Agent 尚未发布或已停止发布，暂时无法对话。请联系创建者重新发布后再试。");
        }
        return new AgentRuntimeConfig(
                published.getAid(), published.getType(), published.getAgentConfig());
    }
}
