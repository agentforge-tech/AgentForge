package cloud.changlu.agentforge.model.fim;

import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimResponse;

/**
 * Provider-neutral 中间补全（Fill-in-the-Middle）模型。
 *
 * <p>与 {@link cloud.changlu.agentforge.model.chat.ChatModel} 并列，而不是复用 ChatModel：
 *
 * <ul>
 *   <li>{@code ChatRequest} 要求 messages 非空，FIM 没有消息概念；
 *   <li>{@code ChatResponse} 必须携带 {@code AiMessage}，FIM 只需要一段文本；
 *   <li>端点是 {@code /completions}，不是 {@code /chat/completions}；
 *   <li>行内补全的延迟要求（首字 &lt; 300ms）与对话完全不同。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/25
 */
@FunctionalInterface
public interface FimModel {

    /**
     * 主入口：一次性拿到完整补全结果。
     *
     * @param request provider-neutral 请求
     * @return 归一化响应
     */
    FimResponse complete(FimRequest request);

    /**
     * 构造可取消调用。
     *
     * <p>默认实现不支持取消，Provider 应覆盖以把取消委托给底层 HTTP 调用。
     */
    default FimCall newCall(final FimRequest request) {
        final FimModel model = this;
        return new FimCall() {
            @Override
            public FimResponse execute() {
                return model.complete(request);
            }

            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    /** 便捷入口：只传前后缀。 */
    default FimResponse complete(String prefix, String suffix) {
        return complete(FimRequest.of(prefix, suffix));
    }
}
