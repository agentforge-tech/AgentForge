package cloud.changlu.agentforge.service.modules.agent.interrupt;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentContextAccessor;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentExecutionContext;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Framework HITL 回调参数转换为 Service 事件内容。 */
@Component
public class AgentInterruptEventContentFactory {
    private final ServiceAgentContextAccessor contextAccessor;

    public AgentInterruptEventContentFactory(ServiceAgentContextAccessor contextAccessor) {
        this.contextAccessor = contextAccessor;
    }

    public Map<String, Object> requested(
            AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        ServiceAgentExecutionContext context = contextAccessor.requireForHitl(chatContext);
        return content(
                suspendInfo.getBatchId(),
                context.getAiMessageId(),
                "PENDING",
                suspendInfo.getInterrupts());
    }

    public Map<String, Object> snapshot(AgentInterruptSnapshot snapshot, String status) {
        ServiceAgentExecutionContext context = contextAccessor.requireForHitl(snapshot);
        return content(
                snapshot.getBatchId(), context.getAiMessageId(), status, snapshot.getInterrupts());
    }

    private static Map<String, Object> content(
            String batchId, Long messageId, String status, List<InterruptRequest> interrupts) {
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("batchId", batchId);
        content.put("messageId", messageId);
        content.put("status", status);
        content.put("interrupts", interruptContents(interrupts));
        return content;
    }

    private static List<Map<String, Object>> interruptContents(List<InterruptRequest> interrupts) {
        List<Map<String, Object>> contents = new ArrayList<Map<String, Object>>();
        for (InterruptRequest interrupt : interrupts) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("interruptId", interrupt.getInterruptId());
            item.put("type", interrupt.getType().name());
            item.put("toolRequest", toolRequestContent(interrupt.getToolRequest()));
            item.put("payload", interrupt.getPayload());
            contents.add(item);
        }
        return contents;
    }

    private static Map<String, Object> toolRequestContent(ToolExecutionRequest request) {
        if (request == null) return null;
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("id", request.id());
        content.put("name", request.name());
        content.put("arguments", request.arguments());
        return content;
    }
}
