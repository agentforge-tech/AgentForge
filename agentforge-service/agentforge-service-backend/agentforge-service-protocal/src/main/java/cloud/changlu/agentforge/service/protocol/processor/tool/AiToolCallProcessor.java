package cloud.changlu.agentforge.service.protocol.processor.tool;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;
import cloud.changlu.agentforge.service.protocol.ToolCallResult;
import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.service.protocol.processor.IStreamSceneProcessor;

/** 工具调用场景处理器。 */
public class AiToolCallProcessor implements IStreamSceneProcessor {

    @Override
    public String getSceneCode() {
        return StreamEventEnums.EventEnum.TOOL_CALL.getCode();
    }

    // 工具调用卡片
    public static StreamEventResponse toolCallCard(
            ToolCallResult toolCallResult, Boolean isFinish) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.TOOL_CALL, StreamEventEnums.TypeEnum.TOOL_CARD)
                .content(toolCallResult)
                .finish(isFinish);
    }
}
