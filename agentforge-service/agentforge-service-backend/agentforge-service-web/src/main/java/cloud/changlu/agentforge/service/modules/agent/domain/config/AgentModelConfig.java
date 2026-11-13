package cloud.changlu.agentforge.service.modules.agent.domain.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Agent 内引用的模型配置。 */
public class AgentModelConfig {

    @JsonProperty("llm_model_id")
    private Long llmModelId;

    @JsonAlias("maxInputToken")
    private Integer maxInputKTokens;

    @JsonAlias("maxOutputToken")
    private Integer maxOutputKTokens;

    private Map<String, String> params;

    public Long getLlmModelId() {
        return llmModelId;
    }

    public void setLlmModelId(Long llmModelId) {
        this.llmModelId = llmModelId;
    }

    public Integer getMaxInputKTokens() {
        return maxInputKTokens;
    }

    public void setMaxInputKTokens(Integer maxInputKTokens) {
        this.maxInputKTokens = maxInputKTokens;
    }

    public Integer getMaxOutputKTokens() {
        return maxOutputKTokens;
    }

    public void setMaxOutputKTokens(Integer maxOutputKTokens) {
        this.maxOutputKTokens = maxOutputKTokens;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params;
    }
}
