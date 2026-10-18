package cloud.changlu.agentforge.service.modules.model.domain.vo;

/**
 * LLM 模型测试结果返回对象。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ModelConfigTestVo {

    private boolean success;
    private String message;
    private Long modelId;
    private Integer testType;

    public static ModelConfigTestVo success(String message) {
        ModelConfigTestVo vo = new ModelConfigTestVo();
        vo.setSuccess(true);
        vo.setMessage(message);
        return vo;
    }

    public static ModelConfigTestVo failure(String message) {
        ModelConfigTestVo vo = new ModelConfigTestVo();
        vo.setSuccess(false);
        vo.setMessage(message);
        return vo;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getModelId() {
        return modelId;
    }

    public void setModelId(Long modelId) {
        this.modelId = modelId;
    }

    public Integer getTestType() {
        return testType;
    }

    public void setTestType(Integer testType) {
        this.testType = testType;
    }
}
