package cloud.changlu.agentforge.service.modules.model.domain.vo;

/** LLM 模型配置详情，仅用于单条详情查询。 */
public class ModelConfigDetailVo extends ModelConfigVo {

    private String apiKey;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
