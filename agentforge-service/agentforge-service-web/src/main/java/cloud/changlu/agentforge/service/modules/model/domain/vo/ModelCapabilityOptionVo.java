package cloud.changlu.agentforge.service.modules.model.domain.vo;

/** 模型下拉选项中供页面使用的轻量能力配置。 */
public class ModelCapabilityOptionVo {
    private Integer maxInputKTokens;
    private Integer maxOutputKTokens;

    public ModelCapabilityOptionVo() {}

    public ModelCapabilityOptionVo(Integer maxInputKTokens, Integer maxOutputKTokens) {
        this.maxInputKTokens = maxInputKTokens;
        this.maxOutputKTokens = maxOutputKTokens;
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
}
