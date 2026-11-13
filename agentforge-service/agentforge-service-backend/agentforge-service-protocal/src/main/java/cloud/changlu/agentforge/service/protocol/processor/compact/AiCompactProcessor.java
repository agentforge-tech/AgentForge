package cloud.changlu.agentforge.service.protocol.processor.compact;

import cloud.changlu.agentforge.service.protocol.CompactResult;
import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.service.protocol.processor.IStreamSceneProcessor;

/** 上下文压缩场景处理器。 */
public class AiCompactProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.COMPACT.getCode();
    }

    public static StreamEventResponse compacting(CompactResult result) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.COMPACT, StreamEventEnums.TypeEnum.COMPACTING)
                .title("正在压缩上下文")
                .content(result)
                .finish(false);
    }

    public static StreamEventResponse compacted(CompactResult result) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.COMPACT, StreamEventEnums.TypeEnum.COMPACTED)
                .title("上下文已压缩")
                .content(result)
                .finish(false);
    }

    public static StreamEventResponse failed(CompactResult result) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.COMPACT,
                        StreamEventEnums.TypeEnum.COMPACT_FAILED)
                .title("上下文压缩失败")
                .content(result)
                .finish(false);
    }
}
