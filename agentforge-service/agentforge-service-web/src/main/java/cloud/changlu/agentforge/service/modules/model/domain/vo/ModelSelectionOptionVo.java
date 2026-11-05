package cloud.changlu.agentforge.service.modules.model.domain.vo;

/** Agent 等业务场景使用的模型下拉选项。 */
public class ModelSelectionOptionVo {
    private Long id;
    private String name;
    private String modelName;
    private Integer modelType;
    private ModelCapabilityOptionVo modelConfig;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getModelType() {
        return modelType;
    }

    public void setModelType(Integer modelType) {
        this.modelType = modelType;
    }

    public ModelCapabilityOptionVo getModelConfig() {
        return modelConfig;
    }

    public void setModelConfig(ModelCapabilityOptionVo modelConfig) {
        this.modelConfig = modelConfig;
    }
}
