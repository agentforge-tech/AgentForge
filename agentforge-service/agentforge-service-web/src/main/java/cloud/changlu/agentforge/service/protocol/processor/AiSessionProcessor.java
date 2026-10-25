package cloud.changlu.agentforge.service.protocol.processor;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;

/** 会话场景处理器。 */
public class AiSessionProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.SESSION.getCode();
    }

    // 会话创建
    public static StreamEventResponse sessionCreated(String sessionId) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.SESSION, StreamEventEnums.TypeEnum.CREATED)
                .content(sessionId)
                .finish(false);
    }
}
