package cloud.changlu.agentforge.agent.component.state.compensation;

import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 恢复执行前修复不完整的消息链。
 *
 * <p>只补齐缺失的 tool 结果，不伪造模型输出；正常 runState 恢复场景下通常不会触发。
 */
public final class MessageChainRepairer {

    private static final String REPAIR_TEXT = "会话恢复：该工具调用未完成，按中断处理";

    private MessageChainRepairer() {}

    /** 判断是否为框架写入的合成 AiMessage（取消/恢复通知），恢复时应从历史中剔除。 */
    public static boolean isSyntheticAiMessage(ChatMessage message) {
        if (!(message instanceof AiMessage)) return false;
        Object value = ((AiMessage) message).attributes().get("agentforge.synthetic");
        return Boolean.TRUE.equals(value);
    }

    /**
     * 为最近一条工具调用消息补齐缺失的结果消息。
     *
     * @param memory 会话记忆
     * @param previousExecutionId 来源执行ID，用于追踪
     * @return 是否发生了补齐
     */
    public static boolean repairDanglingToolCalls(ChatMemory memory, String previousExecutionId) {
        if (memory == null) return false;
        List<ChatMessage> messages = memory.messages();
        if (messages == null || messages.isEmpty()) return false;

        AiMessage toolCallingMessage = latestToolCallingMessage(messages);
        if (toolCallingMessage == null) return false;

        Set<String> completedIds = completedToolIds(messages, toolCallingMessage);
        boolean repaired = false;
        for (ToolExecutionRequest request : toolCallingMessage.toolExecutionRequests()) {
            if (completedIds.contains(request.id())) continue;
            memory.add(
                    ToolExecutionResultMessage.builder()
                            .id(request.id())
                            .toolName(request.name())
                            .text(REPAIR_TEXT)
                            .isError(true)
                            .attributes(attributes(previousExecutionId))
                            .build());
            repaired = true;
        }
        return repaired;
    }

    private static AiMessage latestToolCallingMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.type() == ChatMessageType.USER) return null;
            if (message.type() == ChatMessageType.AI) {
                AiMessage ai = (AiMessage) message;
                return ai.hasToolExecutionRequests() ? ai : null;
            }
        }
        return null;
    }

    private static Set<String> completedToolIds(
            List<ChatMessage> messages, AiMessage toolCallingMessage) {
        Set<String> ids = new HashSet<String>();
        int aiIndex = messages.lastIndexOf(toolCallingMessage);
        for (int i = aiIndex + 1; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
                ids.add(((ToolExecutionResultMessage) message).id());
            }
        }
        return ids;
    }

    private static Map<String, Object> attributes(String previousExecutionId) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("agentforge.synthetic", Boolean.TRUE);
        values.put("agentforge.terminalState", "RESUMED");
        values.put("agentforge.previousExecutionId", previousExecutionId);
        values.put("agentforge.reason", REPAIR_TEXT);
        return values;
    }
}
