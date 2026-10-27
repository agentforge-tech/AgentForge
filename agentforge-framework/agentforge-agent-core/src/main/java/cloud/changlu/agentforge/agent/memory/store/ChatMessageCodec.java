package cloud.changlu.agentforge.agent.memory.store;

import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.Content;
import cloud.changlu.agentforge.model.chat.message.ContentType;
import cloud.changlu.agentforge.model.chat.message.CustomMessage;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.TextContent;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @description 消息列表与 JSON 之间的编解码器，供 MySQL、Redis 等持久化实现复用。
 *     <p>编码结果只包含 AgentForge 自身的 {@link ChatMessage} 字段，不依赖任何厂商的序列化格式，因此不存在反序列化白名单问题； 解码按 {@code
 *     type} 还原具体消息类型，未知类型会显式抛异常而不静默降级。
 * @author changlu
 * @date 2026/10/25
 */
public final class ChatMessageCodec {

    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_THINKING = "thinking";
    private static final String FIELD_TOOL_REQUESTS = "toolRequests";
    private static final String FIELD_TOOL_RESULT = "toolResult";
    private static final String FIELD_CONTENTS = "contents";
    private static final String FIELD_ATTRIBUTES = "attributes";
    private static final String FIELD_CONTENT_TYPE = "contentType";

    /** 把消息列表编码为 JSON 数组字符串 */
    public String encodeAll(List<? extends ChatMessage> messages) {
        List<Object> encoded = new ArrayList<Object>();
        if (messages != null) {
            for (ChatMessage message : messages) {
                encoded.add(toMap(message));
            }
        }
        return Json.stringify(encoded);
    }

    /** 把 JSON 数组字符串解码为消息列表；空内容返回空列表 */
    public List<ChatMessage> decodeAll(String json) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        if (json == null || json.trim().isEmpty()) {
            return messages;
        }
        List<Object> array = Json.array(Json.parse(json));
        if (array == null) {
            throw new IllegalStateException("Chat message JSON must be an array");
        }
        for (Object element : array) {
            messages.add(fromValue(element));
        }
        return messages;
    }

    /** 把单条消息转换为可序列化为 JSON 的 Map */
    public Map<String, Object> toMap(ChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(FIELD_TYPE, message.type().name());
        switch (message.type()) {
            case SYSTEM:
                map.put(FIELD_TEXT, message.text());
                break;
            case USER:
                putUserMessage(map, (UserMessage) message);
                break;
            case AI:
                putAiMessage(map, (AiMessage) message);
                break;
            case TOOL_EXECUTION_RESULT:
                putToolResult(map, (ToolExecutionResultMessage) message);
                break;
            case CUSTOM:
                map.put(FIELD_ATTRIBUTES, ((CustomMessage) message).attributes());
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported chat message type: " + message.type());
        }
        return map;
    }

    private void putUserMessage(Map<String, Object> map, UserMessage message) {
        if (message.name() != null) {
            map.put(FIELD_NAME, message.name());
        }
        if (message.hasSingleText()) {
            map.put(FIELD_TEXT, message.text());
            return;
        }
        List<Object> contents = new ArrayList<Object>();
        for (Content content : message.contents()) {
            Map<String, Object> encoded = new LinkedHashMap<String, Object>();
            encoded.put(FIELD_CONTENT_TYPE, content.type().name());
            if (!(content instanceof TextContent)) {
                throw new IllegalArgumentException(
                        "Unsupported chat content type: " + content.type());
            }
            encoded.put(FIELD_TEXT, ((TextContent) content).text());
            contents.add(encoded);
        }
        map.put(FIELD_CONTENTS, contents);
    }

    private void putAiMessage(Map<String, Object> map, AiMessage message) {
        map.put(FIELD_TEXT, message.text());
        if (message.thinking() != null) {
            map.put(FIELD_THINKING, message.thinking());
        }
        if (message.hasToolExecutionRequests()) {
            List<Object> requests = new ArrayList<Object>();
            for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                Map<String, Object> encoded = new LinkedHashMap<String, Object>();
                encoded.put("id", request.id());
                encoded.put("name", request.name());
                encoded.put("arguments", request.arguments());
                requests.add(encoded);
            }
            map.put(FIELD_TOOL_REQUESTS, requests);
        }
        if (!message.attributes().isEmpty()) {
            map.put(FIELD_ATTRIBUTES, message.attributes());
        }
    }

    private void putToolResult(Map<String, Object> map, ToolExecutionResultMessage message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", message.id());
        result.put("toolName", message.toolName());
        result.put("text", message.text());
        if (message.isError() != null) {
            result.put("isError", message.isError());
        }
        map.put(FIELD_TOOL_RESULT, result);
        if (!message.attributes().isEmpty()) {
            map.put(FIELD_ATTRIBUTES, message.attributes());
        }
    }

    private ChatMessage fromValue(Object value) {
        Map<String, Object> map = Json.object(value);
        if (map == null) {
            throw new IllegalStateException("Chat message JSON must be an object");
        }
        ChatMessageType type = parseType(Json.string(map.get(FIELD_TYPE)));
        switch (type) {
            case SYSTEM:
                return new SystemMessage(Json.string(map.get(FIELD_TEXT)));
            case USER:
                return decodeUserMessage(map);
            case AI:
                return decodeAiMessage(map);
            case TOOL_EXECUTION_RESULT:
                return decodeToolResult(map);
            case CUSTOM:
                return new CustomMessage(Json.object(map.get(FIELD_ATTRIBUTES)));
            default:
                throw new IllegalStateException("Unsupported chat message type: " + type);
        }
    }

    private ChatMessageType parseType(String type) {
        if (type == null) {
            throw new IllegalStateException("Chat message JSON has no type");
        }
        try {
            return ChatMessageType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown chat message type: " + type, e);
        }
    }

    private UserMessage decodeUserMessage(Map<String, Object> map) {
        String name = Json.string(map.get(FIELD_NAME));
        List<Object> encodedContents = Json.array(map.get(FIELD_CONTENTS));
        if (encodedContents == null) {
            return new UserMessage(name, Json.string(map.get(FIELD_TEXT)));
        }
        List<Content> contents = new ArrayList<Content>();
        for (Object element : encodedContents) {
            contents.add(decodeContent(element));
        }
        return new UserMessage(name, contents);
    }

    private Content decodeContent(Object value) {
        Map<String, Object> map = Json.object(value);
        if (map == null) {
            throw new IllegalStateException("Chat message content JSON must be an object");
        }
        String contentType = Json.string(map.get(FIELD_CONTENT_TYPE));
        if (ContentType.TEXT.name().equals(contentType)) {
            return new TextContent(Json.string(map.get(FIELD_TEXT)));
        }
        throw new IllegalStateException("Unsupported chat content type: " + contentType);
    }

    private AiMessage decodeAiMessage(Map<String, Object> map) {
        AiMessage.Builder builder =
                AiMessage.builder()
                        .text(Json.string(map.get(FIELD_TEXT)))
                        .thinking(Json.string(map.get(FIELD_THINKING)))
                        .attributes(Json.object(map.get(FIELD_ATTRIBUTES)));
        List<Object> encodedRequests = Json.array(map.get(FIELD_TOOL_REQUESTS));
        if (encodedRequests != null && !encodedRequests.isEmpty()) {
            List<ToolExecutionRequest> requests = new ArrayList<ToolExecutionRequest>();
            for (Object element : encodedRequests) {
                Map<String, Object> request = Json.object(element);
                if (request == null) {
                    throw new IllegalStateException("Tool request JSON must be an object");
                }
                requests.add(
                        ToolExecutionRequest.from(
                                Json.string(request.get("id")),
                                Json.string(request.get("name")),
                                Json.string(request.get("arguments"))));
            }
            builder.toolExecutionRequests(requests);
        }
        return builder.build();
    }

    private ToolExecutionResultMessage decodeToolResult(Map<String, Object> map) {
        Map<String, Object> result = Json.object(map.get(FIELD_TOOL_RESULT));
        if (result == null) {
            throw new IllegalStateException("Tool result JSON must contain toolResult");
        }
        Object isError = result.get("isError");
        return ToolExecutionResultMessage.builder()
                .id(Json.string(result.get("id")))
                .toolName(Json.string(result.get("toolName")))
                .text(Json.string(result.get("text")))
                .isError(isError instanceof Boolean ? (Boolean) isError : null)
                .attributes(Json.object(map.get(FIELD_ATTRIBUTES)))
                .build();
    }
}
