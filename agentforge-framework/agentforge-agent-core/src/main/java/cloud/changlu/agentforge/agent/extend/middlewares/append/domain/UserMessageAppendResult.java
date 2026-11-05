package cloud.changlu.agentforge.agent.extend.middlewares.append.domain;

/** 追加消息入队结果。 */
public final class UserMessageAppendResult {
    public enum Code {
        QUEUED,
        INVALID_ARGUMENT,
        RUN_NOT_OPEN,
        RUN_ALREADY_CLOSED,
        QUEUE_FULL
    }

    private final Code code;
    private final AppendedUserMessage message;
    private final int queuePosition;

    private UserMessageAppendResult(Code code, AppendedUserMessage message, int queuePosition) {
        this.code = code;
        this.message = message;
        this.queuePosition = queuePosition;
    }

    public static UserMessageAppendResult queued(AppendedUserMessage message, int position) {
        return new UserMessageAppendResult(Code.QUEUED, message, position);
    }

    public static UserMessageAppendResult rejected(Code code) {
        return new UserMessageAppendResult(code, null, 0);
    }

    public boolean isQueued() {
        return code == Code.QUEUED;
    }

    public Code getCode() {
        return code;
    }

    public AppendedUserMessage getMessage() {
        return message;
    }

    public int getQueuePosition() {
        return queuePosition;
    }
}
