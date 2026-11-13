package cloud.changlu.agentforge.service.modules.agent.enums;

import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;

/**
 * 追加消息生命周期状态及其 SSE 协议映射。
 *
 * <p>{@link #QUEUED} 是唯一的非终态；一条追加消息最终只能进入 EFFECTIVE、DEFERRED、CANCELED 中的一个状态。
 */
public enum UserMessageAppendStatus {
    /** QUEUED：消息已入队，等待在下一个模型调用边界注入 Agent Loop。 */
    QUEUED(1, StreamEventEnums.TypeEnum.APPEND_QUEUED, false),

    /** EFFECTIVE：消息已注入 Agent Loop，并对后续模型调用生效。 */
    EFFECTIVE(2, StreamEventEnums.TypeEnum.APPEND_EFFECTIVE, true),

    /** DEFERRED：当前 Agent Loop 已结束，消息未能在本轮生效。 */
    DEFERRED(3, StreamEventEnums.TypeEnum.APPEND_DEFERRED, true),

    /** CANCELED：消息在生效前已被用户取消。 */
    CANCELED(4, StreamEventEnums.TypeEnum.APPEND_CANCELED, true);

    private final int code;
    private final StreamEventEnums.TypeEnum eventType;
    private final boolean finish;

    UserMessageAppendStatus(int code, StreamEventEnums.TypeEnum eventType, boolean finish) {
        this.code = code;
        this.eventType = eventType;
        this.finish = finish;
    }

    /** 持久化状态值：1 待生效、2 已生效、3 已延后、4 已取消。 */
    public int getCode() {
        return code;
    }

    public StreamEventEnums.TypeEnum getEventType() {
        return eventType;
    }

    public boolean isFinish() {
        return finish;
    }
}
