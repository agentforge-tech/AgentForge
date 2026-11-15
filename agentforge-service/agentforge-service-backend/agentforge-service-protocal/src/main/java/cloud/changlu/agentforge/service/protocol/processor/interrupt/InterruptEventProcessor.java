package cloud.changlu.agentforge.service.protocol.processor.interrupt;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;

/** HITL 中断事件构造器。 */
public final class InterruptEventProcessor {

    private InterruptEventProcessor() {}

    public static StreamEventResponse requested(Object content) {
        return event(StreamEventEnums.TypeEnum.INTERRUPT_REQUESTED, "等待您处理", content);
    }

    public static StreamEventResponse resuming(Object content) {
        return event(StreamEventEnums.TypeEnum.INTERRUPT_RESUMING, "正在继续执行", content);
    }

    public static StreamEventResponse resumed(Object content) {
        return event(StreamEventEnums.TypeEnum.INTERRUPT_RESUMED, "已继续执行", content);
    }

    public static StreamEventResponse failed(Object content) {
        return event(StreamEventEnums.TypeEnum.INTERRUPT_RESUME_FAILED, "恢复失败", content);
    }

    public static StreamEventResponse cancelled(Object content) {
        return event(StreamEventEnums.TypeEnum.INTERRUPT_CANCELLED, "已取消", content);
    }

    private static StreamEventResponse event(
            StreamEventEnums.TypeEnum type, String title, Object content) {
        return StreamEventResponse.of(StreamEventEnums.EventEnum.INTERRUPT, type)
                .title(title)
                .content(content)
                .finish(false);
    }
}
