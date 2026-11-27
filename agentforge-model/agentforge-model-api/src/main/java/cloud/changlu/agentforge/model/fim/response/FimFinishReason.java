package cloud.changlu.agentforge.model.fim.response;

/**
 * 补全结束原因，与各厂商的 {@code finish_reason} 归一。
 *
 * <p>相比 {@link cloud.changlu.agentforge.model.chat.response.FinishReason}，这里去掉了 {@code
 * TOOL_EXECUTION}（FIM 不涉及工具调用），并新增两个 FIM 场景必须区分的取值：
 *
 * <ul>
 *   <li>{@link #RESOURCE_LIMIT}：后端推理资源受限，属于可重试错误；
 *   <li>{@link #ABORTED}：生成被中断，通常由客户端取消引起，不应视为失败。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/25
 */
public enum FimFinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    /** 后端推理资源受限（DeepSeek {@code insufficient_system_resource}），可重试。 */
    RESOURCE_LIMIT,
    /** 生成被中断（DeepSeek {@code aborted}），通常是客户端取消，不重试。 */
    ABORTED,
    OTHER
}
