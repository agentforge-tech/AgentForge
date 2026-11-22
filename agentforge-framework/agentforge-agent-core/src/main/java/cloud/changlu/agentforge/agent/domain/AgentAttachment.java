package cloud.changlu.agentforge.agent.domain;

/**
 * 随一条用户消息携带的附件，由业务层解析后传入框架层。
 *
 * <p>{@code payloadType} 决定该附件进入模型请求时的形态：{@link AttachmentPayloadType#URL} 和外链图片使用 {@link
 * #url}；{@link AttachmentPayloadType#INLINE} 由框架层按需读取本地文件转 Base64；{@link
 * AttachmentPayloadType#TEXT} 直接使用业务层预先准备好的 {@link #text}。
 *
 * @author changlu
 * @since 2026-11-21
 */
public final class AgentAttachment {

    private final Long fileId;
    private final String name;
    private final AgentAttachmentType type;
    private final String fileType;
    private final Long size;
    private final String url;
    private final AttachmentPayloadType payloadType;
    private final String text;

    private AgentAttachment(Builder builder) {
        this.fileId = builder.fileId;
        this.name = builder.name;
        this.type = builder.type == null ? AgentAttachmentType.UNKNOWN : builder.type;
        this.fileType = builder.fileType;
        this.size = builder.size;
        this.url = builder.url;
        this.payloadType =
                builder.payloadType == null ? AttachmentPayloadType.TEXT : builder.payloadType;
        this.text = builder.text;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getFileId() {
        return fileId;
    }

    public String getName() {
        return name;
    }

    public AgentAttachmentType getType() {
        return type;
    }

    public String getFileType() {
        return fileType;
    }

    public Long getSize() {
        return size;
    }

    public String getUrl() {
        return url;
    }

    public AttachmentPayloadType getPayloadType() {
        return payloadType;
    }

    public String getText() {
        return text;
    }

    public static final class Builder {
        private Long fileId;
        private String name;
        private AgentAttachmentType type;
        private String fileType;
        private Long size;
        private String url;
        private AttachmentPayloadType payloadType;
        private String text;

        private Builder() {}

        public Builder fileId(Long fileId) {
            this.fileId = fileId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder type(AgentAttachmentType type) {
            this.type = type;
            return this;
        }

        public Builder fileType(String fileType) {
            this.fileType = fileType;
            return this;
        }

        public Builder size(Long size) {
            this.size = size;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder payloadType(AttachmentPayloadType payloadType) {
            this.payloadType = payloadType;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public AgentAttachment build() {
            return new AgentAttachment(this);
        }
    }
}
