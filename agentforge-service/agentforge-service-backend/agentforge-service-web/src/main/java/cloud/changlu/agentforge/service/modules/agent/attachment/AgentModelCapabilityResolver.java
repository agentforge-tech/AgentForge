package cloud.changlu.agentforge.service.modules.agent.attachment;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;
import cloud.changlu.agentforge.agent.config.LlmModelConfigRegistry;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentRuntimeConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 解析 Agent 当前对话模型的能力与协议码。
 *
 * <p>能力信息来源仍是 {@link LlmModelConfigRegistry}，业务代码不维护模型白名单。
 *
 * @author changlu
 * @since 2026-11-21
 */
@Service
public class AgentModelCapabilityResolver {

    private final ModelConfigMapper modelConfigMapper;
    private final ObjectMapper objectMapper;

    public AgentModelCapabilityResolver(
            ModelConfigMapper modelConfigMapper, ObjectMapper objectMapper) {
        this.modelConfigMapper = modelConfigMapper;
        this.objectMapper = objectMapper;
    }

    /** 解析 Agent 运行配置对应的模型能力；解析失败返回 {@code null}，调用方按「不支持图片」保守处理。 */
    public AgentModelCapability resolve(AgentRuntimeConfig config) {
        if (config == null || config.getAgentConfig() == null) {
            return null;
        }
        Long llmModelId = resolveLlmModelId(config.getAgentConfig());
        if (llmModelId == null) {
            return null;
        }
        ModelConfigPojo pojo = modelConfigMapper.selectById(llmModelId);
        if (pojo == null) {
            return null;
        }
        LlmModelConfig modelConfig =
                LlmModelConfigRegistry.registry().getConfig(pojo.getUrl(), pojo.getModelname());
        return new AgentModelCapability(modelConfig, pojo.getProvider());
    }

    private Long resolveLlmModelId(String agentConfig) {
        try {
            StandardAgentConfig standard =
                    objectMapper.readValue(agentConfig, StandardAgentConfig.class);
            AgentModelConfig chatModel = standard.getChatModel();
            return chatModel == null ? null : chatModel.getLlmModelId();
        } catch (Exception e) {
            return null;
        }
    }
}
