package cloud.changlu.agentforge.model.fim.response;

/**
 * 接收流式补全的生命周期回调。
 *
 * <p>刻意不复用 {@link cloud.changlu.agentforge.model.chat.response.StreamingChatResponseHandler}：
 * 后者必须回调 {@code ChatResponse}，会把 {@code AiMessage} 拖进 FIM 协议。
 *
 * <p>实现应保持轻量，Provider 可能从 HTTP I/O 线程回调。
 *
 * @author changlu
 * @date 2026/11/25
 */
public interface StreamingFimResponseHandler {

    /**
     * 首个非空片段。
     *
     * <p>单独回调是为了让上层无需自己判断"是不是第一次"，直接统计 TTFT。
     *
     * @param chunk 首个非空增量
     */
    default void onFirstChunk(FimChunk chunk) {}

    /** 每个增量片段，非空。 */
    void onPartialCompletion(FimChunk chunk);

    /** 成功结束后回调一次，携带聚合后的完整响应与 usage。 */
    void onComplete(FimResponse completeResponse);

    /** 流式失败时回调；取消导致的异常不应回调此方法。 */
    void onError(Throwable error);
}
