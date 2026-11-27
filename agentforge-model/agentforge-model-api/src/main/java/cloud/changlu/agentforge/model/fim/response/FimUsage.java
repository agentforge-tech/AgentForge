package cloud.changlu.agentforge.model.fim.response;

/**
 * 统一 Token 用量。
 *
 * <p>与 {@link cloud.changlu.agentforge.model.chat.response.TokenUsage} 语义一致，`cachedInputTokens`
 * 对应各协议的缓存命中字段：
 *
 * <ul>
 *   <li>DeepSeek Completions：{@code usage.prompt_cache_hit_tokens}
 *   <li>OpenAI Completions：{@code usage.prompt_tokens_details.cached_tokens}
 * </ul>
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class FimUsage {

    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final long cachedInputTokens;

    public FimUsage(long inputTokens, long outputTokens, long totalTokens, long cachedInputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cachedInputTokens = cachedInputTokens;
    }

    public static FimUsage of(long inputTokens, long outputTokens) {
        return new FimUsage(inputTokens, outputTokens, inputTokens + outputTokens, 0L);
    }

    public static FimUsage of(long inputTokens, long outputTokens, long cachedInputTokens) {
        return new FimUsage(
                inputTokens, outputTokens, inputTokens + outputTokens, cachedInputTokens);
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }

    public long cachedInputTokens() {
        return cachedInputTokens;
    }

    /** 缓存命中率；无输入时返回 0，避免除零。 */
    public double cacheHitRatio() {
        return inputTokens <= 0 ? 0d : (double) cachedInputTokens / (double) inputTokens;
    }
}
