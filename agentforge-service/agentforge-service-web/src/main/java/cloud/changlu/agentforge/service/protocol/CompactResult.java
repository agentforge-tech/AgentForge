package cloud.changlu.agentforge.service.protocol;

/** 上下文压缩事件内容，供前端展示压缩状态与耗时。 */
public class CompactResult {

    private Boolean success;
    private String status;
    private Integer currentStep;
    private String trigger;
    private Long duration;
    private Integer beforeMessages;
    private Integer afterMessages;
    private Integer beforeTokens;
    private Integer afterTokens;
    private Integer maxContextTokens;
    private Double beforeUsagePercent;
    private Double afterUsagePercent;
    private Integer savedTokens;
    private Double savedPercent;
    private String triggerDescription;
    private String errorMessage;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(Integer currentStep) {
        this.currentStep = currentStep;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    public Integer getBeforeMessages() {
        return beforeMessages;
    }

    public void setBeforeMessages(Integer beforeMessages) {
        this.beforeMessages = beforeMessages;
    }

    public Integer getAfterMessages() {
        return afterMessages;
    }

    public void setAfterMessages(Integer afterMessages) {
        this.afterMessages = afterMessages;
    }

    public Integer getBeforeTokens() {
        return beforeTokens;
    }

    public void setBeforeTokens(Integer beforeTokens) {
        this.beforeTokens = beforeTokens;
    }

    public Integer getAfterTokens() {
        return afterTokens;
    }

    public void setAfterTokens(Integer afterTokens) {
        this.afterTokens = afterTokens;
    }

    public Integer getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(Integer maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public Double getBeforeUsagePercent() {
        return beforeUsagePercent;
    }

    public void setBeforeUsagePercent(Double beforeUsagePercent) {
        this.beforeUsagePercent = beforeUsagePercent;
    }

    public Double getAfterUsagePercent() {
        return afterUsagePercent;
    }

    public void setAfterUsagePercent(Double afterUsagePercent) {
        this.afterUsagePercent = afterUsagePercent;
    }

    public Integer getSavedTokens() {
        return savedTokens;
    }

    public void setSavedTokens(Integer savedTokens) {
        this.savedTokens = savedTokens;
    }

    public Double getSavedPercent() {
        return savedPercent;
    }

    public void setSavedPercent(Double savedPercent) {
        this.savedPercent = savedPercent;
    }

    public String getTriggerDescription() {
        return triggerDescription;
    }

    public void setTriggerDescription(String triggerDescription) {
        this.triggerDescription = triggerDescription;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
