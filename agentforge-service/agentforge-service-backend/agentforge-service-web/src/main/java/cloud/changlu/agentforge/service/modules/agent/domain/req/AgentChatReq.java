package cloud.changlu.agentforge.service.modules.agent.domain.req;

import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.service.domain.req.ChatFileRef;

import java.util.List;

/** Agent 正式对话与调试对话的统一请求。 */
public class AgentChatReq {

    private String aid;
    private String sessionId;
    private String executionId;
    private String message;
    private Boolean isDebug;

    /** 服务端鉴权得到的执行所属用户，不接收前端传入。 */
    private transient Long userId;

    /** 本次消息引用的文件列表，包含 fileId 与展示元信息。 */
    private List<ChatFileRef> files;

    /** 已解析的附件，由 Controller/Service 填充，不接收前端直接传入。 */
    private transient List<AgentAttachment> attachments;

    /** 本次请求初始化得到的 AI 消息主键，用于会话生命周期中间件收口。 */
    private Long aiMessageId;

    /** 会话表主键。 */

    /** 会话标题，仅首次创建时有值。 */
    private String sessionTitle;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getIsDebug() {
        return isDebug;
    }

    public void setIsDebug(Boolean isDebug) {
        this.isDebug = isDebug;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAiMessageId() {
        return aiMessageId;
    }

    public void setAiMessageId(Long aiMessageId) {
        this.aiMessageId = aiMessageId;
    }

    public String getSessionTitle() {
        return sessionTitle;
    }

    public void setSessionTitle(String sessionTitle) {
        this.sessionTitle = sessionTitle;
    }

    /** 本次消息实际引用的附件主键。 */
    public List<Long> resolveFileIds() {
        return ChatFileRef.extractFileIds(files);
    }

    public List<ChatFileRef> getFiles() {
        return files;
    }

    public void setFiles(List<ChatFileRef> files) {
        this.files = files;
    }

    public List<AgentAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<AgentAttachment> attachments) {
        this.attachments = attachments;
    }
}
