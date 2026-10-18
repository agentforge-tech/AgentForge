package cloud.changlu.agentforge.service.modules.model.domain.req;

/**
 * LLM 模型连通性 / Function Call 测试请求。
 *
 * <p>{@code modelId} 不为空时使用数据库中已保存的配置；否则使用请求体内联的模型配置进行测试。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ModelConfigTestReq {

    private Long modelId;
    private Integer provider;
    private Integer modelType;
    private String modelname;
    private String url;
    private String apiKey;
    private Integer testType;

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public Integer getProvider() {
        return provider;
    }

    public void setProvider(Integer provider) {
        this.provider = provider;
    }

    public Integer getModelType() {
        return modelType;
    }

    public void setModelType(Integer modelType) {
        this.modelType = modelType;
    }

    public String getModelname() {
        return modelname;
    }

    public void setModelname(String modelname) {
        this.modelname = modelname;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Integer getTestType() {
        return testType;
    }

    public void setTestType(Integer testType) {
        this.testType = testType;
    }
}
