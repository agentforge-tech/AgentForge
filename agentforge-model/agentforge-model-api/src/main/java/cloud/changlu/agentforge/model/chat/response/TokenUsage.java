package cloud.changlu.agentforge.model.chat.response;

/**
 * 统一 Token 用量。
 *
 * <p>{@code cachedTokens} 表示本次请求命中缓存（缓存读取）的输入 token 数，按各协议官方字段归一：
 *
 * <ul>
 *   <li>OpenAI Chat Completions：{@code usage.prompt_tokens_details.cached_tokens}（{@code
 *       prompt_tokens} 已包含缓存命中部分）
 *   <li>OpenAI Responses：{@code usage.input_tokens_details.cached_tokens}
 *   <li>Anthropic：{@code usage.cache_read_input_tokens}（{@code input_tokens} 不含缓存部分， 总输入需再加上 {@code
 *       cache_creation_input_tokens}）
 *   <li>DeepSeek：{@code usage.prompt_cache_hit_tokens}
 * </ul>
 *
 * <p>缓存命中率统一按 {@code cachedTokens / inputTokens} 计算。
 *
 * @see <a href="https://platform.openai.com/docs/guides/prompt-caching">OpenAI Prompt caching</a>
 * @see <a
 *     href="https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching#tracking-cache-performance">Anthropic
 *     Prompt caching - Tracking cache performance</a>
 * @see <a href="https://api-docs.deepseek.com/guides/kv_cache">DeepSeek Context Caching</a>
 */
public final class TokenUsage {

    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
    private final long cachedTokens;

    public TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
        this(inputTokens, outputTokens, totalTokens, 0L);
    }

    public TokenUsage(long inputTokens, long outputTokens, long totalTokens, long cachedTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = cachedTokens;
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return of(inputTokens, outputTokens, 0L);
    }

    public static TokenUsage of(long inputTokens, long outputTokens, long cachedTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens, cachedTokens);
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

    public long cachedTokens() {
        return cachedTokens;
    }
}
