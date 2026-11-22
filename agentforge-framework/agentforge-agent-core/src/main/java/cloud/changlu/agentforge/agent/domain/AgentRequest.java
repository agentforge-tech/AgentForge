package cloud.changlu.agentforge.agent.domain;

import java.util.List;
import java.util.Map;

/**
 * @description Agent运行请求参数
 * @author changlu
 * @date 2026/9/16
 */
public class AgentRequest {

    private Object memoryId;

    /** Framework 单次执行标识；为空时由 Agent 自动生成。 */
    private String executionId;

    private String question;

    /** 业务侧扩展参数，由上层放入，Agent 内部不解释，仅在 ChatContext 中透传。 */
    private Map<String, Object> extensions;

    /** 本次消息携带的附件；为空表示纯文本对话。 */
    private List<AgentAttachment> attachments;

    public AgentRequest() {}

    private AgentRequest(AgentRequestBuilder builder) {
        this.memoryId = builder.memoryId;
        this.executionId = builder.executionId;
        this.question = builder.question;
        this.extensions = builder.extensions;
        this.attachments = builder.attachments;
    }

    public static AgentRequestBuilder builder() {
        return new AgentRequestBuilder();
    }

    public Object getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(Object memoryId) {
        this.memoryId = memoryId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    public List<AgentAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AgentAttachment> attachments) {
        this.attachments = attachments;
    }

    public static class AgentRequestBuilder {
        private Object memoryId;
        private String executionId;
        private String question;
        private Map<String, Object> extensions;
        private List<AgentAttachment> attachments;

        public AgentRequestBuilder memoryId(Object memoryId) {
            this.memoryId = memoryId;
            return this;
        }

        public AgentRequestBuilder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public AgentRequestBuilder question(String question) {
            this.question = question;
            return this;
        }

        public AgentRequestBuilder extensions(java.util.Map<String, Object> extensions) {
            this.extensions = extensions;
            return this;
        }

        public AgentRequestBuilder attachments(java.util.List<AgentAttachment> attachments) {
            this.attachments = attachments;
            return this;
        }

        public AgentRequest build() {
            if (memoryId == null) {
                throw new IllegalArgumentException("memoryId must not be null");
            }
            if (question == null || question.trim().isEmpty()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            return new AgentRequest(this);
        }
    }
}
