package cloud.changlu.agentforge.service.protocol.processor.retry;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.service.protocol.processor.IStreamSceneProcessor;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.concurrent.TimeUnit;

/** AI 模型调用重试的流式事件构造器。 */
public class AiRetryProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.RETRY.getCode();
    }

    /**
     * 构造一次模型调用重试事件。
     *
     * @param currentStep 当前轮次（从 1 开始）
     * @param retryCount 本次是第几次重试（从 1 开始）
     * @param maxRetries 最大重试次数
     * @param delayMs 本次重试前的等待时间（毫秒）
     */
    public static StreamEventResponse retry(
            int currentStep, int retryCount, int maxRetries, long delayMs) {
        long eventAt = System.currentTimeMillis();
        long safeDelayMs = Math.max(delayMs, 0L);
        EventContent content = new EventContent();
        content.setCurrentStep(currentStep);
        content.setRetryCount(retryCount);
        content.setMaxRetries(maxRetries);
        content.setDelayMs(safeDelayMs);
        content.setDelaySeconds(TimeUnit.MILLISECONDS.toSeconds(safeDelayMs));
        content.setEventAt(eventAt);
        content.setRetryAt(eventAt + safeDelayMs);
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.RETRY, StreamEventEnums.TypeEnum.RETRY)
                .content(content)
                .finish(false);
    }

    /** retry SSE 事件内容，仅由当前 Processor 构造。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class EventContent {
        /** 当前 Agent Loop 轮次，从 1 开始。 */
        private Integer currentStep;

        /** 本次是第几次重试，从 1 开始。 */
        private Integer retryCount;

        /** 配置的最大重试次数。 */
        private Integer maxRetries;

        /** 本次重试前的等待时间，毫秒。 */
        private Long delayMs;

        /** 本次重试前的等待时间，秒；用于前端直接展示“x 秒后重试”。 */
        private Long delaySeconds;

        /** 事件产生时间，Unix 毫秒时间戳；与 retryAt 配合计算本次等待时长。 */
        private Long eventAt;

        /** 预计开始重试的时间，Unix 毫秒时间戳；前端可据此做倒计时。 */
        private Long retryAt;

        public Integer getCurrentStep() {
            return currentStep;
        }

        public void setCurrentStep(Integer currentStep) {
            this.currentStep = currentStep;
        }

        public Integer getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(Integer retryCount) {
            this.retryCount = retryCount;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(Long delayMs) {
            this.delayMs = delayMs;
        }

        public Long getDelaySeconds() {
            return delaySeconds;
        }

        public void setDelaySeconds(Long delaySeconds) {
            this.delaySeconds = delaySeconds;
        }

        public Long getEventAt() {
            return eventAt;
        }

        public void setEventAt(Long eventAt) {
            this.eventAt = eventAt;
        }

        public Long getRetryAt() {
            return retryAt;
        }

        public void setRetryAt(Long retryAt) {
            this.retryAt = retryAt;
        }
    }
}
