package cloud.changlu.agentforge.service.domain.req;

import java.util.List;

public class ChatStreamRequest {
    private String aid;
    private String sessionId;
    private String message;
    private Boolean isDebug;

    /** 本次消息引用的文件列表，包含 fileId 与展示元信息。 */
    private List<ChatFileRef> files;

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
}
