package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.service.modules.agent.event.store.MessageEventStore;
import cloud.changlu.agentforge.service.modules.agent.event.store.StoredMessageEvent;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class MessageEventSnapshotService {
    private final MessageEventStore eventStore;
    private final AgentSessionMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public MessageEventSnapshotService(
            MessageEventStore eventStore,
            AgentSessionMessageMapper messageMapper,
            ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    public String saveSnapshot(long messageId) {
        ArrayNode array = objectMapper.createArrayNode();
        try {
            for (StoredMessageEvent event : eventStore.readAll(messageId)) {
                JsonNode node = objectMapper.readTree(event.getPayload());
                array.add(node);
            }
            String json = objectMapper.writeValueAsString(array);
            messageMapper.updateEvents(messageId, json, new Date());
            return json;
        } catch (Exception e) {
            throw new IllegalStateException("生成消息事件快照失败, messageId=" + messageId, e);
        }
    }
}
