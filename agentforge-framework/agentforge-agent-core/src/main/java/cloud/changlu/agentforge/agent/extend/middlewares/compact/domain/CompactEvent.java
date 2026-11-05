package cloud.changlu.agentforge.agent.extend.middlewares.compact.domain;

/** 一次上下文压缩的运行时事件，供上层适配 SSE、WebSocket 或其他交付方式。 */
public final class CompactEvent {

    private final Object memoryId;
    private final int step;
    private final CompactTrigger trigger;
    private final boolean success;
    private final long durationMs;
    private final int beforeMessages;
    private final int afterMessages;
    private final int beforeTokens;
    private final int afterTokens;
    private final int maxContextTokens;
    private final String errorMessage;

    private CompactEvent(
            Object memoryId,
            int step,
            CompactTrigger trigger,
            boolean success,
            long durationMs,
            int beforeMessages,
            int afterMessages,
            int beforeTokens,
            int afterTokens,
            int maxContextTokens,
            String errorMessage) {
        this.memoryId = memoryId;
        this.step = step;
        this.trigger = trigger;
        this.success = success;
        this.durationMs = durationMs;
        this.beforeMessages = beforeMessages;
        this.afterMessages = afterMessages;
        this.beforeTokens = beforeTokens;
        this.afterTokens = afterTokens;
        this.maxContextTokens = maxContextTokens;
        this.errorMessage = errorMessage;
    }

    public static CompactEvent success(
            Object memoryId,
            int step,
            CompactTrigger trigger,
            long durationMs,
            int beforeMessages,
            int afterMessages,
            int beforeTokens,
            int afterTokens,
            int maxContextTokens) {
        return new CompactEvent(
                memoryId,
                step,
                trigger,
                true,
                durationMs,
                beforeMessages,
                afterMessages,
                beforeTokens,
                afterTokens,
                maxContextTokens,
                null);
    }

    public static CompactEvent failure(
            Object memoryId,
            int step,
            CompactTrigger trigger,
            long durationMs,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            String errorMessage) {
        return new CompactEvent(
                memoryId,
                step,
                trigger,
                false,
                durationMs,
                beforeMessages,
                beforeMessages,
                beforeTokens,
                beforeTokens,
                maxContextTokens,
                errorMessage);
    }

    public Object getMemoryId() {
        return memoryId;
    }

    public int getStep() {
        return step;
    }

    public CompactTrigger getTrigger() {
        return trigger;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getBeforeMessages() {
        return beforeMessages;
    }

    public int getAfterMessages() {
        return afterMessages;
    }

    public int getBeforeTokens() {
        return beforeTokens;
    }

    public int getAfterTokens() {
        return afterTokens;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public double getBeforeUsagePercent() {
        return maxContextTokens <= 0 ? 0 : (double) beforeTokens / maxContextTokens * 100;
    }

    public double getAfterUsagePercent() {
        return maxContextTokens <= 0 ? 0 : (double) afterTokens / maxContextTokens * 100;
    }
}
