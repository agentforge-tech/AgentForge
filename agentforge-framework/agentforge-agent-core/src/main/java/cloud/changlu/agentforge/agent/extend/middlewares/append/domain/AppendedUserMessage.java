package cloud.changlu.agentforge.agent.extend.middlewares.append.domain;

/** Agent Loop 运行期间追加的一条用户消息。 */
public final class AppendedUserMessage {
    private final Long appendId;
    private final String content;
    private final long queuedAt;

    public AppendedUserMessage(Long appendId, String content, long queuedAt) {
        this.appendId = appendId;
        this.content = content;
        this.queuedAt = queuedAt;
    }

    public Long getAppendId() {
        return appendId;
    }

    public String getContent() {
        return content;
    }

    public long getQueuedAt() {
        return queuedAt;
    }
}
