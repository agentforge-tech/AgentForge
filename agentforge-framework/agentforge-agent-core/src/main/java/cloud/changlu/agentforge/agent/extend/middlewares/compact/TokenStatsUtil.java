package cloud.changlu.agentforge.agent.extend.middlewares.compact;

import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.SystemMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;

import java.util.List;

public final class TokenStatsUtil {

    private static final int ASCII_UNITS = 1;
    private static final int WIDE_UNITS = 4;
    private static final int UNITS_PER_TOKEN = 4;

    private static final int SYSTEM_OVERHEAD = 10;
    private static final int USER_OVERHEAD = 5;
    private static final int AI_OVERHEAD = 15;
    private static final int TOOL_OVERHEAD = 20;
    private static final int TOOL_CALL_EXTRA = 20;

    private static final String[] SENTENCE_BOUNDARIES = {"。", "！", "？", "\n", ".", "!", "?"};

    private TokenStatsUtil() {}

    public static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += isWideChar(text.charAt(i)) ? WIDE_UNITS : ASCII_UNITS;
        }
        return Math.max(units / UNITS_PER_TOKEN, 1);
    }

    public static int estimateMessageTokens(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        int tokens = estimateTextTokens(getMessageContent(message));
        if (message.type() == ChatMessageType.SYSTEM) {
            tokens += SYSTEM_OVERHEAD;
        } else if (message.type() == ChatMessageType.USER) {
            tokens += USER_OVERHEAD;
        } else if (message.type() == ChatMessageType.AI) {
            tokens += AI_OVERHEAD;
            AiMessage aiMessage = (AiMessage) message;
            tokens += aiMessage.toolExecutionRequests().size() * TOOL_CALL_EXTRA;
        } else if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
            tokens += TOOL_OVERHEAD;
        }
        return Math.max(tokens, 1);
    }

    public static int estimateTotalTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateMessageTokens(message);
        }
        return total;
    }

    public static String getMessageContent(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message.type() == ChatMessageType.SYSTEM) {
            return ((SystemMessage) message).text();
        }
        if (message.type() == ChatMessageType.USER) {
            UserMessage userMessage = (UserMessage) message;
            if (userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
            return String.valueOf(userMessage.contents());
        }
        if (message.type() == ChatMessageType.AI) {
            AiMessage aiMessage = (AiMessage) message;
            if (aiMessage.text() != null && !aiMessage.text().isEmpty()) {
                return aiMessage.text();
            }
            int toolCalls = aiMessage.toolExecutionRequests().size();
            return toolCalls == 0 ? "" : "[tool calls: " + toolCalls + "]";
        }
        if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
            return ((ToolExecutionResultMessage) message).text();
        }
        return message.toString();
    }

    public static String getRoleName(ChatMessage message) {
        if (message == null) {
            return "unknown";
        }
        if (message.type() == ChatMessageType.SYSTEM) {
            return "system";
        }
        if (message.type() == ChatMessageType.USER) {
            return "user";
        }
        if (message.type() == ChatMessageType.AI) {
            return "assistant";
        }
        if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
            return "tool";
        }
        return "custom";
    }

    public static String truncateText(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (maxTokens <= 0) {
            return "";
        }
        if (estimateTextTokens(text) <= maxTokens) {
            return text;
        }

        int budgetUnits = maxTokens * UNITS_PER_TOKEN;
        int usedUnits = 0;
        int cutIndex = 0;
        for (int i = 0; i < text.length(); i++) {
            int cost = isWideChar(text.charAt(i)) ? WIDE_UNITS : ASCII_UNITS;
            if (usedUnits + cost > budgetUnits) {
                break;
            }
            usedUnits += cost;
            cutIndex = i + 1;
        }

        if (cutIndex <= 0) {
            cutIndex = Math.min(1, text.length());
        }
        int boundary = findSentenceBoundary(text, cutIndex);
        if (boundary > cutIndex * 7 / 10) {
            cutIndex = boundary;
        }
        return text.substring(0, cutIndex) + "...[truncated]";
    }

    public static ChatMessage createTruncatedMessage(
            ChatMessage original, String truncatedContent) {
        if (original == null) {
            return null;
        }
        if (original.type() == ChatMessageType.SYSTEM) {
            return SystemMessage.from(truncatedContent);
        }
        if (original.type() == ChatMessageType.USER) {
            return UserMessage.from(truncatedContent);
        }
        if (original.type() == ChatMessageType.AI) {
            return AiMessage.from(truncatedContent);
        }
        if (original.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
            ToolExecutionResultMessage toolMessage = (ToolExecutionResultMessage) original;
            return new ToolExecutionResultMessage(
                    toolMessage.id(), toolMessage.toolName(), truncatedContent);
        }
        return original;
    }

    public static String formatConversation(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            builder.append(getRoleName(message))
                    .append(": ")
                    .append(getMessageContent(message))
                    .append('\n');
        }
        return builder.toString();
    }

    public static String formatConversation(List<ChatMessage> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int currentTokens = 0;
        for (ChatMessage message : messages) {
            String role = getRoleName(message);
            String content = getMessageContent(message);
            String line = role + ": " + content + "\n";
            int lineTokens = estimateTextTokens(line);

            if (currentTokens + lineTokens > maxTokens) {
                int remaining = maxTokens - currentTokens;
                if (remaining > 1) {
                    int contentBudget = Math.max(remaining - estimateTextTokens(role + ": \n"), 1);
                    builder.append(role)
                            .append(": ")
                            .append(truncateText(content, contentBudget))
                            .append('\n');
                }
                break;
            }

            builder.append(line);
            currentTokens += lineTokens;
        }
        return builder.toString();
    }

    public static TokenStats calculate(List<ChatMessage> messages, int maxContextTokens) {
        if (messages == null || messages.isEmpty()) {
            return new TokenStats(0, 0, 0, 0, 0, maxContextTokens);
        }

        int totalTokens = 0;
        int systemTokens = 0;
        int userTokens = 0;
        int aiTokens = 0;
        int toolTokens = 0;

        for (ChatMessage message : messages) {
            int tokens = estimateMessageTokens(message);
            totalTokens += tokens;
            if (message.type() == ChatMessageType.SYSTEM) {
                systemTokens += tokens;
            } else if (message.type() == ChatMessageType.USER) {
                userTokens += tokens;
            } else if (message.type() == ChatMessageType.AI) {
                aiTokens += tokens;
            } else if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
                toolTokens += tokens;
            }
        }

        return new TokenStats(
                totalTokens, systemTokens, userTokens, aiTokens, toolTokens, maxContextTokens);
    }

    public static double getTotalPercentage(List<ChatMessage> messages, int maxContextTokens) {
        return calculate(messages, maxContextTokens).getTotalPercent();
    }

    public static boolean needCompress(
            List<ChatMessage> messages, int maxContextTokens, int thresholdPercent) {
        if (maxContextTokens <= 0) {
            return false;
        }
        double percentage = (double) estimateTotalTokens(messages) / maxContextTokens * 100;
        return percentage >= thresholdPercent;
    }

    private static boolean isWideChar(char value) {
        return value >= 0x2E80;
    }

    private static int findSentenceBoundary(String text, int fromIndex) {
        int best = -1;
        for (String boundary : SENTENCE_BOUNDARIES) {
            int index = text.lastIndexOf(boundary, Math.max(fromIndex - 1, 0));
            if (index >= 0 && index + 1 > best) {
                best = index + 1;
            }
        }
        return best;
    }

    public static final class TokenStats {

        private final int totalTokens;
        private final int systemTokens;
        private final int userTokens;
        private final int aiTokens;
        private final int toolTokens;
        private final int maxContextTokens;

        private final double totalPercentage;
        private final double systemPercentage;
        private final double userPercentage;
        private final double aiPercentage;
        private final double toolPercentage;

        public TokenStats(
                int totalTokens,
                int systemTokens,
                int userTokens,
                int aiTokens,
                int toolTokens,
                int maxContextTokens) {
            this.totalTokens = totalTokens;
            this.systemTokens = systemTokens;
            this.userTokens = userTokens;
            this.aiTokens = aiTokens;
            this.toolTokens = toolTokens;
            this.maxContextTokens = maxContextTokens;

            int safeMax = maxContextTokens <= 0 ? 1 : maxContextTokens;
            this.totalPercentage = (double) totalTokens / safeMax * 100;
            this.systemPercentage = (double) systemTokens / safeMax * 100;
            this.userPercentage = (double) userTokens / safeMax * 100;
            this.aiPercentage = (double) aiTokens / safeMax * 100;
            this.toolPercentage = (double) toolTokens / safeMax * 100;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        public int getSystemTokens() {
            return systemTokens;
        }

        public int getUserTokens() {
            return userTokens;
        }

        public int getAiTokens() {
            return aiTokens;
        }

        public int getToolTokens() {
            return toolTokens;
        }

        public int getMaxContextTokens() {
            return maxContextTokens;
        }

        public double getTotalPercent() {
            return totalPercentage;
        }

        public double getSystemPercent() {
            return systemPercentage;
        }

        public double getUserPercent() {
            return userPercentage;
        }

        public double getAiPercent() {
            return aiPercentage;
        }

        public double getToolPercent() {
            return toolPercentage;
        }

        public boolean isOverThreshold(int thresholdPercent) {
            return totalPercentage >= thresholdPercent;
        }

        public String getDistribution() {
            return String.format(
                    "total:%d(%.1f%%) system:%d(%.1f%%) user:%d(%.1f%%) ai:%d(%.1f%%) tool:%d(%.1f%%)",
                    totalTokens,
                    totalPercentage,
                    systemTokens,
                    systemPercentage,
                    userTokens,
                    userPercentage,
                    aiTokens,
                    aiPercentage,
                    toolTokens,
                    toolPercentage);
        }

        @Override
        public String toString() {
            return "TokenStats{"
                    + "total="
                    + totalTokens
                    + "("
                    + String.format("%.1f", totalPercentage)
                    + "%), max="
                    + maxContextTokens
                    + ", "
                    + getDistribution()
                    + '}';
        }
    }
}
