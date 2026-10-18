package cloud.changlu.agentforge.service.modules.agent.domain.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Agent 内引用的模型配置。 */
public class AgentModelConfig {

    @JsonProperty("llm_model_id")
    private Long llmModelId;

    private Integer maxInputToken;
    private Integer maxOutputToken;
    private Map<String, String> params;

    public Long getLlmModelId() {
        return llmModelId;
    }

    public void setLlmModelId(Long llmModelId) {
        this.llmModelId = llmModelId;
    }

    public Integer getMaxInputToken() {
        return maxInputToken;
    }

    public void setMaxInputToken(Integer maxInputToken) {
        this.maxInputToken = maxInputToken;
    }

    public Integer getMaxOutputToken() {
        return maxOutputToken;
    }

    public void setMaxOutputToken(Integer maxOutputToken) {
        this.maxOutputToken = maxOutputToken;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params;
    }
}
