package cloud.changlu.agentforge.agent.component.state.compensation;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
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

/** 取消时补齐 User-AI、AI(tool calls)-ToolResults 消息链。 */
public final class CancellationMessageCompensator {
    private static final String CANCEL_TEXT = "本次 Agent 执行已取消";

    private CancellationMessageCompensator() {}

    public static void compensate(AgentChatContext context, String reason) {
        List<ChatMessage> messages = context.getChatMemory().messages();
        if (messages == null || messages.isEmpty()) return;
        ChatMessage last = messages.get(messages.size() - 1);
        Map<String, Object> attributes = attributes(context, reason);
        if (last.type() == ChatMessageType.USER) {
            context.getChatMemory()
                    .add(AiMessage.builder().text(CANCEL_TEXT).attributes(attributes).build());
            return;
        }

        AiMessage toolCallingMessage = latestToolCallingMessage(messages);
        if (toolCallingMessage == null) return;
        Set<String> completedIds = completedToolIds(messages, toolCallingMessage);
        boolean compensated = false;
        for (ToolExecutionRequest request : toolCallingMessage.toolExecutionRequests()) {
            if (completedIds.contains(request.id())) continue;
            context.getChatMemory()
                    .add(
                            ToolExecutionResultMessage.builder()
                                    .id(request.id())
                                    .toolName(request.name())
                                    .text("工具调用因 Agent 取消而中断")
                                    .isError(true)
                                    .attributes(attributes)
                                    .build());
            compensated = true;
        }
        if (compensated) {
            context.getChatMemory()
                    .add(AiMessage.builder().text(CANCEL_TEXT).attributes(attributes).build());
        }
    }

    private static AiMessage latestToolCallingMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.type() == ChatMessageType.AI) {
                AiMessage ai = (AiMessage) message;
                return ai.hasToolExecutionRequests() ? ai : null;
            }
            if (message.type() == ChatMessageType.USER) return null;
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

    private static Map<String, Object> attributes(AgentChatContext context, String reason) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("agentforge.synthetic", Boolean.TRUE);
        values.put("agentforge.terminalState", "CANCELLED");
        values.put("agentforge.executionId", context.getExecutionId());
        values.put("agentforge.reason", reason == null ? CANCEL_TEXT : reason);
        return values;
    }
}
