package cloud.changlu.agentforge.agent.component.state.domain;

/**
 * Agent 单次执行当前所处的 ReAct 阶段。
 *
 * <p>phase 只描述“执行位置”，不描述生命周期；需与 {@link AgentExecutionStatus} 组合理解，例如： {@code RUNNING +
 * MODEL_CALL}、{@code CANCEL_REQUESTED + COMPENSATING}。
 */
public enum AgentExecutionPhase {

    /** 运行控制已创建，尚未进入 ReAct 循环；流式场景下对应“已返回 TokenStream 但未 start”。 */
    INITIALIZING,

    /** 一轮循环开始前：执行 beforeLoop 中间件、刷新系统消息、构建本轮模型请求。 */
    BEFORE_LOOP,

    /** 正在请求模型：同步等待响应，或流式接收 token/思考增量；该阶段可能持续较长时间。 */
    MODEL_CALL,

    /** 模型调用失败后的指数退避等待：尚未发起下一次请求，取消时可直接跳过重试。 */
    MODEL_RETRY_WAIT,

    /** 正在执行本批工具：包含顺序执行与并发执行，直到本批工具全部返回或取消。 */
    TOOL_CALL,

    /** 工具批次已完成，准备进入下一轮 think 的过渡阶段。 */
    BETWEEN_STEPS,

    /** 已收到取消/中断，正在补齐 ChatMemory 中的 User-AI、AI(tool calls)-ToolResults 消息链。 */
    COMPENSATING,

    /** 已得到最终结果或终态，正在收口中间件回调、写入 resultState 与终态。 */
    FINALIZING,

    /** 收口完成，运行已进入终态；具体是 COMPLETED/CANCELLED/FAILED 由 status 决定。 */
    FINISHED
}
