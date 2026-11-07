package cloud.changlu.agentforge.agent.component.state.domain;

/**
 * Agent 单次执行的生命周期状态。
 *
 * <p>status 只描述“整体处于哪个阶段”，不描述具体执行位置；具体位置由 {@link AgentExecutionPhase} 表达， 结束原因由 {@link
 * cloud.changlu.agentforge.agent.domain.AgentRunState} 表达。
 */
public enum AgentExecutionStatus {

    /** 运行控制已创建，尚未进入 ReAct 初始化；流式场景下对应“已返回 TokenStream 但未 start”。 */
    CREATED,

    /** 正在进行 ReAct 执行；当前具体在模型调用还是工具调用，看 phase。 */
    RUNNING,

    /**
     * 已受理取消，正在关闭模型/工具句柄、中断线程并补偿消息链。
     *
     * <p>该状态不是终态：对调用方应立即可见，但最终结果还需等待收口完成。
     */
    CANCEL_REQUESTED,

    /** 正常结束，或达到最大步数后按既定结果结束；具体结束原因看 resultState。 */
    COMPLETED,

    /** 取消收口完成：消息补偿和 onStop(CANCEL) 已执行。 */
    CANCELLED,

    /** 未被框架转化为正常结果的异常终止；错误摘要看 message。 */
    FAILED
}
