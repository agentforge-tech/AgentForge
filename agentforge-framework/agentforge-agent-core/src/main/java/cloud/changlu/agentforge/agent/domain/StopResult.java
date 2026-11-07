package cloud.changlu.agentforge.agent.domain;

import cloud.changlu.agentforge.agent.step.StopResultState;

/**
 * @description Agent停止结果：正常停止携带result，取消/限制等停止携带reason
 * @author changlu
 * @date 2026/9/16
 */
public class StopResult {

    // 聊天结果状态
    private StopResultState stopResultState;

    // 正常停止时的最终运行结果
    private String result;

    // 取消、最大步数等非正常停止的原因
    private String reason;

    public StopResult() {}

    private StopResult(Builder builder) {
        this.stopResultState = builder.stopResultState;
        this.result = builder.result;
        this.reason = builder.reason;
    }

    public static Builder builder() {
        return new Builder();
    }

    public StopResultState getStopResultState() {
        return stopResultState;
    }

    public void setStopResultState(StopResultState stopResultState) {
        this.stopResultState = stopResultState;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "StopResult{stopResultState="
                + stopResultState
                + ", result='"
                + result
                + '\''
                + ", reason='"
                + reason
                + '\''
                + '}';
    }

    public static class Builder {
        private StopResultState stopResultState;
        private String result;
        private String reason;

        public Builder stopResultState(StopResultState stopResultState) {
            this.stopResultState = stopResultState;
            return this;
        }

        public Builder result(String result) {
            this.result = result;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public StopResult build() {
            return new StopResult(this);
        }
    }
}
