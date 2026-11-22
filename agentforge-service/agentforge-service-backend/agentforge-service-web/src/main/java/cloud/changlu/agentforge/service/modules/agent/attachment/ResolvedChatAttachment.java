package cloud.changlu.agentforge.service.modules.agent.attachment;

/**
 * 已完成归属校验的会话附件。
 *
 * <p>只包含展示与路由所需字段，Base64 不在这一层出现。
 *
 * @author changlu
 * @since 2026-11-21
 */
public class ResolvedChatAttachment {

    private final Long fileId;
    private final String name;
    private final String visitUrl;
    private final String fileType;
    private final Long fileSize;

    public ResolvedChatAttachment(
            Long fileId, String name, String visitUrl, String fileType, Long fileSize) {
        this.fileId = fileId;
        this.name = name;
        this.visitUrl = visitUrl;
        this.fileType = fileType;
        this.fileSize = fileSize;
    }

    public Long getFileId() {
        return fileId;
    }

    public String getName() {
        return name;
    }

    /** 消息快照中的原始文件名字段。 */
    public String getOriginFileName() {
        return name;
    }

    public String getVisitUrl() {
        return visitUrl;
    }

    public String getFileType() {
        return fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }
}
