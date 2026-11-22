package cloud.changlu.agentforge.agent.extend.middlewares.read.middleware;

import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.agent.domain.ImageLoader;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResourceKind;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.support.ReadResultJson;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadResourceTool;
import cloud.changlu.agentforge.agent.support.AttachmentContentAssembler;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 收集读取类工具返回的元数据，并在下一次模型调用前装配为 UserMessage。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ReadArtifactMiddleware implements IAgentMiddleware {

    private static final Pattern PRIVATE_10 = Pattern.compile("^10\\..*");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
    private static final Pattern LOOPBACK_127 = Pattern.compile("^127\\..*");

    private static final String EXT_SUPPORTS_IMAGE = "attachment.supportsImage";
    private static final String EXT_REQUIRES_INLINE = "attachment.requiresInlineImage";

    private final Set<String> readToolNames;
    private final String schema;

    /** 可选的图片加载器覆盖；为空时使用装配器的全局默认实现。 */
    private final ImageLoader imageLoader;

    private final Logger log;
    private final long maxInlineImageBytes;

    /** 模型是否支持图片输入；false 时图片一律降级为文字引用。 */
    private final boolean defaultSupportsImage;

    /**
     * 模型是否要求图片必须内联。
     *
     * <p>部分协议（如 Anthropic）不接受外链图片，只能接收 Base64。该开关为 true 时， 图片会优先走 INLINE（受大小上限约束），而不是直接透传公网 URL。
     */
    private final boolean defaultRequiresInline;

    private final boolean dedupeEnabled;
    private final boolean logEnabled;
    private final Map<String, Deque<ReadResult>> pending =
            new ConcurrentHashMap<String, Deque<ReadResult>>();

    private ReadArtifactMiddleware(Builder builder) {
        this.readToolNames =
                builder.readToolNames == null || builder.readToolNames.isEmpty()
                        ? defaultToolNames()
                        : new LinkedHashSet<String>(builder.readToolNames);
        this.schema = builder.schema == null ? ReadBatchResult.SCHEMA : builder.schema;
        this.imageLoader = builder.imageLoader;
        this.log =
                builder.log == null
                        ? Logger.getLogger(ReadArtifactMiddleware.class.getName())
                        : builder.log;
        this.maxInlineImageBytes = builder.maxInlineImageBytes;
        this.defaultSupportsImage = builder.defaultSupportsImage;
        this.defaultRequiresInline = builder.defaultRequiresInline;
        this.dedupeEnabled = builder.dedupeEnabled;
        this.logEnabled = builder.logEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void onInitComplete(AgentChatContext context) {
        pending.remove(key(context));
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext context) {
        collect(toolRequest, toolResult, context);
        return toolResult;
    }

    @Override
    public List<String> afterConcurrentToolExecution(
            List<ToolExecutionRequest> toolRequests,
            List<String> toolResults,
            AgentChatContext context) {
        if (toolRequests != null && toolResults != null) {
            int size = Math.min(toolRequests.size(), toolResults.size());
            for (int i = 0; i < size; i++) {
                collect(toolRequests.get(i), toolResults.get(i), context);
            }
        }
        return toolResults;
    }

    @Override
    public ChatRequest beforeModelCall(
            int currentStep, ChatRequest request, AgentChatContext context) {
        List<ReadResult> artifacts = drain(context);
        if (artifacts.isEmpty()) {
            return request;
        }
        if (context.getChatMemory() == null) {
            return request;
        }
        List<AgentAttachment> attachments = new ArrayList<AgentAttachment>();
        for (ReadResult artifact : artifacts) {
            AgentAttachment attachment = toAttachment(artifact, context);
            if (attachment != null) {
                attachments.add(attachment);
            }
        }
        if (attachments.isEmpty()) {
            return request;
        }
        fine(
                "read_middleware.before_model step="
                        + currentStep
                        + " attachments="
                        + attachments.size());
        UserMessage message =
                AttachmentContentAssembler.buildUserMessage(
                        "以下是本轮读取到的资源，请结合附件内容继续回答。", attachments, imageLoader);
        context.getChatMemory().add(message);
        return ChatRequest.builder()
                .messages(context.getChatMemory().messages())
                .parameters(request.parameters())
                .build();
    }

    @Override
    public void onStop(int currentStep, StopResult result, AgentChatContext context) {
        pending.remove(key(context));
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext context) {
        pending.remove(key(context));
    }

    private void collect(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext context) {
        if (toolRequest == null || toolResult == null || context == null) {
            return;
        }
        if (!isReadTool(toolRequest.name(), toolResult)) {
            return;
        }
        ReadBatchResult batch = ReadResultJson.parseBatch(toolResult);
        if (batch == null || !batch.isOk()) {
            return;
        }
        if (schema != null && !schema.equals(batch.getSchema())) {
            return;
        }
        int accepted = 0;
        int rejected = 0;
        int duplicated = 0;
        Deque<ReadResult> queue = queue(context);
        synchronized (queue) {
            for (ReadResult result : batch.getResults()) {
                if (result == null || !result.isOk() || result.getUrl() == null) {
                    rejected++;
                    continue;
                }
                if (dedupeEnabled && contains(queue, result.getDedupeKey())) {
                    duplicated++;
                    continue;
                }
                queue.addLast(result);
                accepted++;
            }
        }
        fine(
                "read_middleware.collect tool="
                        + toolRequest.name()
                        + " accepted="
                        + accepted
                        + " rejected="
                        + rejected
                        + " duplicated="
                        + duplicated);
    }

    private boolean isReadTool(String name, String result) {
        if (readToolNames.contains(name)) {
            return true;
        }
        return result.contains(ReadBatchResult.SCHEMA);
    }

    private Deque<ReadResult> queue(AgentChatContext context) {
        String key = key(context);
        Deque<ReadResult> queue = pending.get(key);
        if (queue == null) {
            Deque<ReadResult> created = new ArrayDeque<ReadResult>();
            Deque<ReadResult> existing = pending.putIfAbsent(key, created);
            queue = existing == null ? created : existing;
        }
        return queue;
    }

    private List<ReadResult> drain(AgentChatContext context) {
        Deque<ReadResult> queue = pending.remove(key(context));
        if (queue == null) {
            return new ArrayList<ReadResult>();
        }
        synchronized (queue) {
            return new ArrayList<ReadResult>(queue);
        }
    }

    private static boolean contains(Deque<ReadResult> queue, String dedupeKey) {
        if (dedupeKey == null) {
            return false;
        }
        for (ReadResult item : queue) {
            if (dedupeKey.equals(item.getDedupeKey())) {
                return true;
            }
        }
        return false;
    }

    private AgentAttachment toAttachment(ReadResult result, AgentChatContext context) {
        ReadResourceKind kind =
                result.getKind() == null ? ReadResourceKind.UNKNOWN : result.getKind();
        if (kind.isImage()) {
            AttachmentPayloadType payload = decideImagePayload(result, context);
            AgentAttachment.Builder builder =
                    AgentAttachment.builder()
                            .name(result.getName())
                            .type(AgentAttachmentType.IMAGE)
                            .fileType(result.getFileType())
                            .size(result.getSize())
                            .url(result.getUrl())
                            .payloadType(payload);
            if (payload == AttachmentPayloadType.TEXT) {
                builder.text(referenceText(result, "图片"));
            }
            fine(
                    "read_middleware.convert url="
                            + safe(result.getUrl())
                            + " kind="
                            + kind.code()
                            + " payload="
                            + payload);
            return builder.build();
        }
        String type = kind.isLink() ? "网页链接" : "文件";
        fine(
                "read_middleware.convert url="
                        + safe(result.getUrl())
                        + " kind="
                        + kind.code()
                        + " payload=TEXT");
        return AgentAttachment.builder()
                .name(result.getName())
                .type(kind.isLink() ? AgentAttachmentType.TEXT : AgentAttachmentType.UNKNOWN)
                .fileType(result.getFileType())
                .size(result.getSize())
                .url(result.getUrl())
                .payloadType(AttachmentPayloadType.TEXT)
                .text(referenceText(result, type))
                .build();
    }

    private AttachmentPayloadType decideImagePayload(ReadResult result, AgentChatContext context) {
        String url = result.getUrl();
        if (!booleanExtension(context, EXT_SUPPORTS_IMAGE, defaultSupportsImage)) {
            return AttachmentPayloadType.TEXT;
        }
        if (booleanExtension(context, EXT_REQUIRES_INLINE, defaultRequiresInline)) {
            return inlineOrText(result, "requires_inline");
        }
        if (isPubliclyAccessible(url)) {
            return AttachmentPayloadType.URL;
        }
        return inlineOrText(result, "private_url");
    }

    private AttachmentPayloadType inlineOrText(ReadResult result, String reason) {
        String url = result.getUrl();
        if (result.getSize() != null && result.getSize() > maxInlineImageBytes) {
            warn(
                    "read_middleware.degrade reason=inline_too_large size="
                            + result.getSize()
                            + " max="
                            + maxInlineImageBytes
                            + " url="
                            + safe(url));
            return AttachmentPayloadType.TEXT;
        }
        fine("read_middleware.inline reason=" + reason + " url=" + safe(url));
        return AttachmentPayloadType.INLINE;
    }

    private static boolean booleanExtension(
            AgentChatContext context, String key, boolean defaultValue) {
        Object value = context.getExtension(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private static boolean isPubliclyAccessible(String visitUrl) {
        if (visitUrl == null || visitUrl.trim().isEmpty()) {
            return false;
        }
        try {
            String host = new URI(visitUrl.trim()).getHost();
            if (host == null || host.trim().isEmpty()) {
                return false;
            }
            String value = host.trim().toLowerCase(Locale.ROOT);
            if ("localhost".equals(value) || value.endsWith(".localhost")) {
                return false;
            }
            return !(LOOPBACK_127.matcher(value).matches()
                    || PRIVATE_10.matcher(value).matches()
                    || PRIVATE_172.matcher(value).matches()
                    || PRIVATE_192.matcher(value).matches());
        } catch (Exception e) {
            return false;
        }
    }

    private static String referenceText(ReadResult result, String type) {
        StringBuilder builder = new StringBuilder(type).append("：");
        builder.append(result.getName() == null ? "未命名资源" : result.getName());
        if (result.getFileType() != null && !result.getFileType().trim().isEmpty()) {
            builder.append("（类型 ").append(result.getFileType());
            if (result.getSize() != null) {
                builder.append("，大小 ").append(result.getSize()).append(" bytes");
            }
            builder.append("）");
        }
        if (result.getUrl() != null && !result.getUrl().trim().isEmpty()) {
            builder.append("，访问地址 ").append(result.getUrl());
        }
        return builder.toString();
    }

    private static String key(AgentChatContext context) {
        return String.valueOf(context.getMemoryId()) + ":" + context.getExecutionId();
    }

    private static Set<String> defaultToolNames() {
        Set<String> names = new LinkedHashSet<String>();
        names.add(ReadResourceTool.TOOL_NAME);
        return names;
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        int index = value.indexOf('?');
        return index >= 0 ? value.substring(0, index) + "?..." : value;
    }

    private void fine(String message) {
        if (logEnabled) {
            log.fine(message);
        }
    }

    private void warn(String message) {
        if (logEnabled) {
            log.warning(message);
        }
    }

    public static final class Builder {
        private Set<String> readToolNames;
        private String schema = ReadBatchResult.SCHEMA;
        private ImageLoader imageLoader;
        private Logger log;
        private long maxInlineImageBytes = 4L * 1024 * 1024;
        private boolean defaultSupportsImage = true;
        private boolean defaultRequiresInline = false;
        private boolean dedupeEnabled = true;
        private boolean logEnabled = true;

        private Builder() {}

        public Builder readToolNames(String... names) {
            this.readToolNames = new LinkedHashSet<String>();
            if (names != null) {
                for (String name : names) {
                    if (name != null && !name.trim().isEmpty()) {
                        this.readToolNames.add(name.trim());
                    }
                }
            }
            return this;
        }

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Builder imageLoader(ImageLoader imageLoader) {
            this.imageLoader = imageLoader;
            return this;
        }

        public Builder log(Logger log) {
            this.log = log;
            return this;
        }

        public Builder maxInlineImageBytes(long maxInlineImageBytes) {
            this.maxInlineImageBytes = maxInlineImageBytes;
            return this;
        }

        /** 模型是否支持图片输入；false 时图片统一降级为文字引用。 */
        public Builder supportsImage(boolean supportsImage) {
            this.defaultSupportsImage = supportsImage;
            return this;
        }

        /**
         * 模型是否要求图片必须内联（例如 Anthropic 不支持外链图片）。
         *
         * <p>为 true 时图片优先转 Base64 内联，而不是透传公网 URL；仍受 {@link #maxInlineImageBytes} 限制。
         */
        public Builder requiresInlineImage(boolean requiresInline) {
            this.defaultRequiresInline = requiresInline;
            return this;
        }

        public Builder dedupeEnabled(boolean dedupeEnabled) {
            this.dedupeEnabled = dedupeEnabled;
            return this;
        }

        public Builder logEnabled(boolean logEnabled) {
            this.logEnabled = logEnabled;
            return this;
        }

        public ReadArtifactMiddleware build() {
            return new ReadArtifactMiddleware(this);
        }
    }
}
