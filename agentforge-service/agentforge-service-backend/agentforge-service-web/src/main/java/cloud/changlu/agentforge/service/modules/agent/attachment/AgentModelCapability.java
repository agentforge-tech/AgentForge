package cloud.changlu.agentforge.service.modules.agent.attachment;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;

/**
 * Agent 当前对话模型的能力与协议信息，用于附件载荷路由。
 *
 * @author changlu
 * @since 2026-11-21
 */
public class AgentModelCapability {

    private final LlmModelConfig modelConfig;
    private final Integer providerCode;

    public AgentModelCapability(LlmModelConfig modelConfig, Integer providerCode) {
        this.modelConfig = modelConfig;
        this.providerCode = providerCode;
    }

    public LlmModelConfig getModelConfig() {
        return modelConfig;
    }

    public Integer getProviderCode() {
        return providerCode;
    }
}
