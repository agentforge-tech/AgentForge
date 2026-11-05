package cloud.changlu.agentforge.agent.extend.middlewares.compact;

import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactEvent;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactTrigger;
import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CompactSummarizationMiddleware implements IAgentMiddleware, CompactLifecycleHook {

    private static final Logger logger =
            Logger.getLogger(CompactSummarizationMiddleware.class.getName());

    private static final int MIN_TOOL_RESULT_TOKENS = 100;
    private static final String SUMMARY_MARKER = "[AgentForge Context Summary]";

    private static final String SUMMARY_PROMPT =
            "You are the context compressor for an Agent. Compress the given conversation\n"
                    + "history into a structured working summary that the Agent can use to continue\n"
                    + "the task in later steps.\n"
                    + "\n"
                    + "Extract information along the following dimensions:\n"
                    + "1. Goal: what the user wants to accomplish, the scope, and success criteria;\n"
                    + "2. State: how far the task has progressed, what is done, and what comes next;\n"
                    + "3. Facts: confirmed key entities, parameters, identifiers, and data locations;\n"
                    + "4. Decisions: important decisions and their rationale, including rejected options;\n"
                    + "5. Results: key conclusions and artifacts from tool calls; do not copy large raw outputs;\n"
                    + "6. Open items: unresolved issues, blockers, risks, and items awaiting confirmation;\n"
                    + "7. Constraints: explicit preferences, prohibitions, and change requests from the user.\n"
                    + "\n"
                    + "Requirements:\n"
                    + "- Use only the provided history; do not speculate or add information that is not present;\n"
                    + "- Keep it concise for simple tasks; for complex tasks, preserve subtasks, status,\n"
                    + "  and dependencies under \"Current State\";\n"
                    + "- Omit greetings, repetitive content, and irrelevant details;\n"
                    + "- If an old summary exists, merge it with older history instead of stacking it;\n"
                    + "- Strictly use the following fixed structure, and write \"None\" for empty fields:\n"
                    + "\n"
                    + "[AgentForge Context Summary]\n"
                    + "Goal: ...\n"
                    + "Current State: ...\n"
                    + "Confirmed Facts: ...\n"
                    + "Key Decisions: ...\n"
                    + "Tool Conclusions: ...\n"
                    + "Open Items: ...\n"
                    + "Constraints: ...\n"
                    + "Next Action: ...";

    private final CompactConfig config;

    public CompactSummarizationMiddleware() {
        this(CompactConfig.defaultConfig());
    }

    public CompactSummarizationMiddleware(CompactConfig config) {
        this.config = config == null ? CompactConfig.defaultConfig() : config;
    }

    @Override
    public ChatRequest beforeModelCall(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
        if (chatRequest == null) {
            return null;
        }
        List<ChatMessage> messages = chatRequest.messages();
        if (messages == null || messages.isEmpty()) {
            return chatRequest;
        }

        TokenStatsUtil.TokenStats stats =
                TokenStatsUtil.calculate(messages, config.getMaxContextTokens());
        logger.fine(
                "context token stats, step: "
                        + currentStep
                        + ", max: "
                        + config.getMaxContextTokens()
                        + ", usage: "
                        + String.format("%.1f", stats.getTotalPercent())
                        + "%");

        if (!TokenStatsUtil.needCompress(
                messages, config.getMaxContextTokens(), config.getCompressThreshold())) {
            return chatRequest;
        }

        logger.info(
                "compact triggered, step: "
                        + currentStep
                        + ", tokens: "
                        + stats.getTotalTokens()
                        + "/"
                        + config.getMaxContextTokens()
                        + ", usage: "
                        + String.format("%.1f", stats.getTotalPercent())
                        + "%");

        long startTime = System.currentTimeMillis();
        invokeBeforeCompact(
                currentStep,
                CompactTrigger.MODEL_CALL,
                messages.size(),
                stats.getTotalTokens(),
                chatContext);
        try {
            List<ChatMessage> compressed = performCompression(messages, chatContext);
            replaceMemory(chatContext.getChatMemory(), compressed);

            long durationMs = System.currentTimeMillis() - startTime;
            int compressedTokens = TokenStatsUtil.estimateTotalTokens(compressed);
            logger.info(
                    "compact completed, step: "
                            + currentStep
                            + ", messages: "
                            + messages.size()
                            + " -> "
                            + compressed.size()
                            + ", tokens: "
                            + compressedTokens
                            + "/"
                            + config.getMaxContextTokens()
                            + ", usage: "
                            + String.format(
                                    "%.1f",
                                    (double) compressedTokens / config.getMaxContextTokens() * 100)
                            + "%, duration: "
                            + durationMs
                            + "ms");

            completeCompact(
                    CompactEvent.success(
                            chatContext == null ? null : chatContext.getMemoryId(),
                            currentStep,
                            CompactTrigger.MODEL_CALL,
                            durationMs,
                            messages.size(),
                            compressed.size(),
                            stats.getTotalTokens(),
                            compressedTokens,
                            config.getMaxContextTokens()),
                    chatContext);

            return ChatRequest.builder()
                    .messages(compressed)
                    .parameters(chatRequest.parameters())
                    .build();
        } catch (Exception exception) {
            long durationMs = System.currentTimeMillis() - startTime;
            completeCompact(
                    CompactEvent.failure(
                            chatContext == null ? null : chatContext.getMemoryId(),
                            currentStep,
                            CompactTrigger.MODEL_CALL,
                            durationMs,
                            messages.size(),
                            stats.getTotalTokens(),
                            config.getMaxContextTokens(),
                            exception.getMessage() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getMessage()),
                    chatContext);
            logger.log(
                    Level.WARNING,
                    "compact failed, keep original request, step: " + currentStep,
                    exception);
            return chatRequest;
        }
    }

    private void invokeBeforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            AgentChatContext chatContext) {
        try {
            beforeCompact(
                    currentStep,
                    trigger,
                    beforeMessages,
                    beforeTokens,
                    config.getMaxContextTokens(),
                    chatContext);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "before compact hook failed", exception);
        }
    }

    private void completeCompact(CompactEvent event, AgentChatContext chatContext) {
        try {
            afterCompact(event, chatContext);
        } catch (Exception exception) {
            logger.log(Level.WARNING, "after compact hook failed", exception);
        }
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        if (toolRequest == null || toolResult == null || chatContext == null) {
            return toolResult;
        }

        ChatMemory chatMemory = chatContext.getChatMemory();
        if (chatMemory == null) {
            return toolResult;
        }
        List<ChatMessage> currentMessages = new ArrayList<ChatMessage>(chatMemory.messages());
        currentMessages.add(
                new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), toolResult));

        if (!TokenStatsUtil.needCompress(
                currentMessages, config.getMaxContextTokens(), config.getCompressThreshold())) {
            return toolResult;
        }

        int currentTotalTokens = TokenStatsUtil.estimateTotalTokens(currentMessages);
        long startTime = System.currentTimeMillis();
        invokeBeforeCompact(
                -1,
                CompactTrigger.TOOL_RESULT,
                currentMessages.size(),
                currentTotalTokens,
                chatContext);
        try {
            logger.warning(
                    "tool result exceeds compact threshold, tool: "
                            + toolRequest.name()
                            + ", tokens: "
                            + currentTotalTokens
                            + "/"
                            + config.getMaxContextTokens());

            String truncated = truncateToolResult(toolRequest, toolResult, currentTotalTokens);
            logger.info(
                    "tool result truncated, tool: "
                            + toolRequest.name()
                            + ", tokens: "
                            + TokenStatsUtil.estimateTextTokens(toolResult)
                            + " -> "
                            + TokenStatsUtil.estimateTextTokens(truncated));
            int afterTokens =
                    currentTotalTokens
                            - TokenStatsUtil.estimateTextTokens(toolResult)
                            + TokenStatsUtil.estimateTextTokens(truncated);
            completeCompact(
                    CompactEvent.success(
                            chatContext.getMemoryId(),
                            -1,
                            CompactTrigger.TOOL_RESULT,
                            System.currentTimeMillis() - startTime,
                            currentMessages.size(),
                            currentMessages.size(),
                            currentTotalTokens,
                            afterTokens,
                            config.getMaxContextTokens()),
                    chatContext);
            return truncated;
        } catch (Exception exception) {
            completeCompact(
                    CompactEvent.failure(
                            chatContext.getMemoryId(),
                            -1,
                            CompactTrigger.TOOL_RESULT,
                            System.currentTimeMillis() - startTime,
                            currentMessages.size(),
                            currentTotalTokens,
                            config.getMaxContextTokens(),
                            errorMessage(exception)),
                    chatContext);
            logger.log(
                    Level.WARNING,
                    "tool result truncation failed, keep original result, tool: "
                            + toolRequest.name(),
                    exception);
            return toolResult;
        }
    }

    @Override
    public List<String> afterConcurrentToolExecution(
            List<ToolExecutionRequest> toolRequests,
            List<String> toolResults,
            AgentChatContext chatContext) {
        if (toolRequests == null || toolResults == null || chatContext == null) {
            return toolResults;
        }

        ChatMemory chatMemory = chatContext.getChatMemory();
        if (chatMemory == null) {
            return toolResults;
        }
        List<ChatMessage> currentMessages = new ArrayList<ChatMessage>(chatMemory.messages());
        int size = Math.min(toolRequests.size(), toolResults.size());
        for (int i = 0; i < size; i++) {
            currentMessages.add(
                    new ToolExecutionResultMessage(
                            toolRequests.get(i).id(),
                            toolRequests.get(i).name(),
                            toolResults.get(i) == null ? "" : toolResults.get(i)));
        }

        if (!TokenStatsUtil.needCompress(
                currentMessages, config.getMaxContextTokens(), config.getCompressThreshold())) {
            return toolResults;
        }

        int currentTotalTokens = TokenStatsUtil.estimateTotalTokens(currentMessages);
        long startTime = System.currentTimeMillis();
        invokeBeforeCompact(
                -1,
                CompactTrigger.CONCURRENT_TOOL_RESULT,
                currentMessages.size(),
                currentTotalTokens,
                chatContext);
        try {
            logger.warning(
                    "concurrent tool results exceed compact threshold, tools: "
                            + toolRequests.size()
                            + ", tokens: "
                            + currentTotalTokens
                            + "/"
                            + config.getMaxContextTokens());

            List<String> processed = new ArrayList<String>(toolRequests.size());
            for (int i = 0; i < toolRequests.size(); i++) {
                String result = i < toolResults.size() ? toolResults.get(i) : "";
                processed.add(
                        truncateToolResult(
                                toolRequests.get(i),
                                result == null ? "" : result,
                                currentTotalTokens));
            }
            int afterTokens = TokenStatsUtil.estimateTotalTokens(chatMemory.messages());
            for (int i = 0; i < processed.size(); i++) {
                ToolExecutionRequest request = toolRequests.get(i);
                afterTokens +=
                        TokenStatsUtil.estimateMessageTokens(
                                new ToolExecutionResultMessage(
                                        request.id(), request.name(), processed.get(i)));
            }
            completeCompact(
                    CompactEvent.success(
                            chatContext.getMemoryId(),
                            -1,
                            CompactTrigger.CONCURRENT_TOOL_RESULT,
                            System.currentTimeMillis() - startTime,
                            currentMessages.size(),
                            currentMessages.size(),
                            currentTotalTokens,
                            afterTokens,
                            config.getMaxContextTokens()),
                    chatContext);
            return processed;
        } catch (Exception exception) {
            completeCompact(
                    CompactEvent.failure(
                            chatContext.getMemoryId(),
                            -1,
                            CompactTrigger.CONCURRENT_TOOL_RESULT,
                            System.currentTimeMillis() - startTime,
                            currentMessages.size(),
                            currentTotalTokens,
                            config.getMaxContextTokens(),
                            errorMessage(exception)),
                    chatContext);
            logger.log(
                    Level.WARNING,
                    "concurrent tool result truncation failed, keep original results",
                    exception);
            return toolResults;
        }
    }

    private List<ChatMessage> performCompression(
            List<ChatMessage> messages, AgentChatContext chatContext) {
        List<ChatMessage> systemMessages = new ArrayList<ChatMessage>();
        List<ChatMessage> otherMessages = new ArrayList<ChatMessage>();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                systemMessages.add(message);
            } else {
                otherMessages.add(message);
            }
        }

        int keepTokens = (int) ((long) config.getMaxContextTokens() * config.getKeepRatio() / 100L);
        KeepResult keepResult = selectRecentGroupsFromEnd(otherMessages, keepTokens);

        List<ChatMessage> result = new ArrayList<ChatMessage>();
        result.addAll(systemMessages);
        if (!keepResult.getCompressMessages().isEmpty()) {
            result.add(generateSummary(keepResult.getCompressMessages(), chatContext));
        }
        result.addAll(keepResult.getKeepMessages());
        return result.isEmpty() ? messages : result;
    }

    private KeepResult selectRecentGroupsFromEnd(List<ChatMessage> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            return KeepResult.keepAll(messages);
        }

        List<List<ChatMessage>> groups = groupMessages(messages);
        int totalTokens = 0;
        int keepStartGroupIndex = groups.size();

        for (int i = groups.size() - 1; i >= 0; i--) {
            int groupTokens = 0;
            for (ChatMessage message : groups.get(i)) {
                groupTokens += TokenStatsUtil.estimateMessageTokens(message);
            }

            if (keepStartGroupIndex == groups.size()) {
                keepStartGroupIndex = i;
                totalTokens += groupTokens;
                continue;
            }

            if (totalTokens + groupTokens <= maxTokens) {
                totalTokens += groupTokens;
                keepStartGroupIndex = i;
            } else {
                break;
            }
        }

        if (keepStartGroupIndex == 0) {
            return KeepResult.keepAll(messages);
        }
        return KeepResult.partial(
                flattenGroups(groups.subList(keepStartGroupIndex, groups.size())),
                flattenGroups(groups.subList(0, keepStartGroupIndex)));
    }

    private ChatMessage generateSummary(List<ChatMessage> messages, AgentChatContext chatContext) {
        List<ChatMessage> truncated = truncateByCategory(messages);
        int maxSummaryTokens =
                (int) ((long) config.getMaxContextTokens() * config.getMaxSystemRatio() / 100L);
        String conversation = TokenStatsUtil.formatConversation(truncated, maxSummaryTokens);
        String prompt = buildSummaryPrompt(conversation);

        ChatModel chatModel = chatContext.getChatModel();
        if (chatModel == null) {
            throw new IllegalStateException("ChatModel is null, cannot generate summary");
        }

        String summary = chatModel.chat(prompt);
        if (summary == null) {
            summary = "";
        }
        if (TokenStatsUtil.estimateTextTokens(summary) > maxSummaryTokens) {
            summary = TokenStatsUtil.truncateText(summary, maxSummaryTokens);
        }

        return SystemMessage.from(
                SUMMARY_MARKER
                        + "\n"
                        + summary
                        + "\n\n---\nContinue the task based on the summary above.");
    }

    private List<ChatMessage> truncateByCategory(List<ChatMessage> messages) {
        int maxSystemTokens =
                (int) ((long) config.getMaxContextTokens() * config.getMaxSystemRatio() / 100L);
        int maxOtherTokens =
                (int) ((long) config.getMaxContextTokens() * config.getMaxOtherRatio() / 100L);

        List<ChatMessage> result = new ArrayList<ChatMessage>();
        int systemTokens = 0;
        int otherTokens = 0;
        for (ChatMessage message : messages) {
            int messageTokens = TokenStatsUtil.estimateMessageTokens(message);
            if (message.type() == ChatMessageType.SYSTEM) {
                if (systemTokens + messageTokens <= maxSystemTokens) {
                    result.add(message);
                    systemTokens += messageTokens;
                } else if (systemTokens < maxSystemTokens) {
                    int remaining = maxSystemTokens - systemTokens;
                    result.add(
                            SystemMessage.from(
                                    TokenStatsUtil.truncateText(
                                            TokenStatsUtil.getMessageContent(message), remaining)));
                    systemTokens = maxSystemTokens;
                }
            } else if (message.type() == ChatMessageType.USER) {
                result.add(message);
            } else if (otherTokens + messageTokens <= maxOtherTokens) {
                result.add(message);
                otherTokens += messageTokens;
            } else if (otherTokens < maxOtherTokens) {
                int remaining = maxOtherTokens - otherTokens;
                result.add(
                        TokenStatsUtil.createTruncatedMessage(
                                message,
                                TokenStatsUtil.truncateText(
                                        TokenStatsUtil.getMessageContent(message), remaining)));
                otherTokens = maxOtherTokens;
            }
        }
        return result;
    }

    private void replaceMemory(ChatMemory chatMemory, List<ChatMessage> messages) {
        if (chatMemory == null) {
            return;
        }
        List<ChatMessage> original = new ArrayList<ChatMessage>(chatMemory.messages());
        try {
            chatMemory.clear();
            for (ChatMessage message : messages) {
                chatMemory.add(message);
            }
        } catch (RuntimeException exception) {
            try {
                chatMemory.clear();
                for (ChatMessage message : original) {
                    chatMemory.add(message);
                }
            } catch (RuntimeException rollbackException) {
                logger.log(Level.WARNING, "chat memory rollback failed", rollbackException);
            }
            throw exception;
        }
    }

    private String truncateToolResult(
            ToolExecutionRequest toolRequest, String toolResult, int currentTotalTokens) {
        int targetTotalTokens =
                (int) ((long) config.getMaxContextTokens() * config.getCompressThreshold() / 100L);
        int needReduce = currentTotalTokens - targetTotalTokens;
        if (needReduce <= 0) {
            return toolResult;
        }

        int resultTokens = TokenStatsUtil.estimateTextTokens(toolResult);
        int targetResultTokens = Math.max(resultTokens - needReduce, MIN_TOOL_RESULT_TOKENS);
        String truncated = TokenStatsUtil.truncateText(toolResult, targetResultTokens);

        double currentPercent = (double) currentTotalTokens / config.getMaxContextTokens() * 100;
        return String.format(
                "【工具执行结果已截断】\n"
                        + "上下文使用率: %.1f%% (压缩阈值: %d%%)，为避免超出上下文限制，工具 \"%s\" 的结果已被截断。\n"
                        + "原始估算: %d tokens，截断后: %d tokens。\n"
                        + "建议：缩小查询范围、增加筛选条件或分页获取。\n\n"
                        + "截断后的内容：\n%s",
                currentPercent,
                config.getCompressThreshold(),
                toolRequest.name(),
                resultTokens,
                targetResultTokens,
                truncated);
    }

    private String buildSummaryPrompt(String conversation) {
        return SUMMARY_PROMPT
                + "\n\nConversation history to compress:\n"
                + conversation
                + "\nReturn only the structured summary. Do not wrap it in code fences.";
    }

    private static String errorMessage(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static List<List<ChatMessage>> groupMessages(List<ChatMessage> messages) {
        List<List<ChatMessage>> groups = new ArrayList<List<ChatMessage>>();
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            List<ChatMessage> group = new ArrayList<ChatMessage>();
            group.add(message);

            if (message.type() == ChatMessageType.AI
                    && ((AiMessage) message).hasToolExecutionRequests()) {
                while (index + 1 < messages.size()
                        && messages.get(index + 1).type()
                                == ChatMessageType.TOOL_EXECUTION_RESULT) {
                    group.add(messages.get(++index));
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private static List<ChatMessage> flattenGroups(List<List<ChatMessage>> groups) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (List<ChatMessage> group : groups) {
            messages.addAll(group);
        }
        return messages;
    }

    private static final class KeepResult {

        private final List<ChatMessage> keepMessages;
        private final List<ChatMessage> compressMessages;

        private KeepResult(List<ChatMessage> keepMessages, List<ChatMessage> compressMessages) {
            this.keepMessages = keepMessages;
            this.compressMessages = compressMessages;
        }

        private static KeepResult keepAll(List<ChatMessage> messages) {
            return new KeepResult(
                    messages == null
                            ? new ArrayList<ChatMessage>()
                            : new ArrayList<ChatMessage>(messages),
                    new ArrayList<ChatMessage>());
        }

        private static KeepResult partial(
                List<ChatMessage> keepMessages, List<ChatMessage> compressMessages) {
            return new KeepResult(keepMessages, compressMessages);
        }

        private List<ChatMessage> getKeepMessages() {
            return keepMessages;
        }

        private List<ChatMessage> getCompressMessages() {
            return compressMessages;
        }
    }

    public static class CompactConfig {

        private final int maxContextTokens;
        private final int compressThreshold;
        private final int keepRatio;
        private final int maxSystemRatio;
        private final int maxOtherRatio;

        private CompactConfig(Builder builder) {
            this.maxContextTokens = builder.maxContextTokens;
            this.compressThreshold = builder.compressThreshold;
            this.keepRatio = builder.keepRatio;
            this.maxSystemRatio = builder.maxSystemRatio;
            this.maxOtherRatio = builder.maxOtherRatio;
        }

        public static CompactConfig defaultConfig() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getMaxContextTokens() {
            return maxContextTokens;
        }

        public int getCompressThreshold() {
            return compressThreshold;
        }

        public int getKeepRatio() {
            return keepRatio;
        }

        public int getMaxSystemRatio() {
            return maxSystemRatio;
        }

        public int getMaxOtherRatio() {
            return maxOtherRatio;
        }

        public static class Builder {

            private int maxContextTokens = 1000000;
            private int compressThreshold = 80;
            private int keepRatio = 40;
            private int maxSystemRatio = 30;
            private int maxOtherRatio = 50;

            private Builder() {}

            public Builder maxContextTokens(int maxContextTokens) {
                this.maxContextTokens = maxContextTokens;
                return this;
            }

            public Builder compressThreshold(int compressThreshold) {
                this.compressThreshold = clamp(compressThreshold, 0, 100);
                return this;
            }

            public Builder keepRatio(int keepRatio) {
                this.keepRatio = clamp(keepRatio, 10, 100);
                return this;
            }

            public Builder maxSystemRatio(int maxSystemRatio) {
                this.maxSystemRatio = clamp(maxSystemRatio, 0, 100);
                return this;
            }

            public Builder maxOtherRatio(int maxOtherRatio) {
                this.maxOtherRatio = clamp(maxOtherRatio, 0, 100);
                return this;
            }

            public CompactConfig build() {
                if (maxContextTokens <= 0) {
                    throw new IllegalArgumentException("maxContextTokens must be greater than 0");
                }
                if (maxSystemRatio + maxOtherRatio > 100) {
                    throw new IllegalArgumentException(
                            "maxSystemRatio + maxOtherRatio must not exceed 100");
                }
                return new CompactConfig(this);
            }

            private static int clamp(int value, int min, int max) {
                return Math.min(max, Math.max(min, value));
            }
        }
    }
}
