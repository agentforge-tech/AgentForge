package cloud.changlu.agentforge.service.protocol.processor;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;

/** 异常场景处理器。 */
public class AiErrorProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.ERROR.getCode();
    }

    // 异常信息
    public static StreamEventResponse respError(String content) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.ERROR, StreamEventEnums.TypeEnum.TEXT)
                .content(content)
                .finish(true);
    }
}
