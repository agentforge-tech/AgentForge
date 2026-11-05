package cloud.changlu.agentforge.service.protocol.processor.append;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.service.modules.agent.enums.UserMessageAppendStatus;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.service.protocol.processor.IStreamSceneProcessor;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 用户追加消息的流式事件构造器。 */
public class UserMessageAppendProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.USER_MESSAGE.getCode();
    }

    public static StreamEventResponse queued(AppendedUserMessage message, int position) {
        return event(message, UserMessageAppendStatus.QUEUED, position, null);
    }

    public static StreamEventResponse effective(AppendedUserMessage message, int step) {
        return event(message, UserMessageAppendStatus.EFFECTIVE, null, step);
    }

    public static StreamEventResponse deferred(AppendedUserMessage message) {
        return event(message, UserMessageAppendStatus.DEFERRED, null, null);
    }

    public static StreamEventResponse canceled(AppendedUserMessage message) {
        return event(message, UserMessageAppendStatus.CANCELED, null, null);
    }

    private static StreamEventResponse event(
            AppendedUserMessage message,
            UserMessageAppendStatus status,
            Integer position,
            Integer step) {
        EventContent content = new EventContent();
        content.setAppendId(message.getAppendId());
        content.setContent(message.getContent());
        content.setStatus(status.getCode());
        content.setQueuePosition(position);
        content.setEffectiveStep(step);
        content.setQueuedAt(message.getQueuedAt());
        content.setEventAt(System.currentTimeMillis());
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.USER_MESSAGE, status.getEventType())
                .content(content)
                .finish(status.isFinish());
    }

    /** user_message SSE 事件内容，仅由当前 Processor 构造。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class EventContent {
        /** 追加消息的唯一标识，用于前端关联同一条消息的状态变化及取消请求。 */
        private Long appendId;

        /** 用户追加的原始消息内容。 */
        private String content;

        /** 当前生命周期状态：1 待生效、2 已生效、3 已延后、4 已取消。 */
        private Integer status;

        /** 消息处于 QUEUED 状态时的队列位置，其他状态不返回。 */
        private Integer queuePosition;

        /** 消息进入 Agent Loop 时对应的执行步数，仅 EFFECTIVE 状态返回。 */
        private Integer effectiveStep;

        /** 消息最初进入追加队列的时间，Unix 毫秒时间戳。 */
        private Long queuedAt;

        /** 当前状态事件产生的时间，Unix 毫秒时间戳。 */
        private Long eventAt;

        public Long getAppendId() {
            return appendId;
        }

        public void setAppendId(Long appendId) {
            this.appendId = appendId;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public Integer getQueuePosition() {
            return queuePosition;
        }

        public void setQueuePosition(Integer queuePosition) {
            this.queuePosition = queuePosition;
        }

        public Integer getEffectiveStep() {
            return effectiveStep;
        }

        public void setEffectiveStep(Integer effectiveStep) {
            this.effectiveStep = effectiveStep;
        }

        public Long getQueuedAt() {
            return queuedAt;
        }

        public void setQueuedAt(Long queuedAt) {
            this.queuedAt = queuedAt;
        }

        public Long getEventAt() {
            return eventAt;
        }

        public void setEventAt(Long eventAt) {
            this.eventAt = eventAt;
        }
    }
}
