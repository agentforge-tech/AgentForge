package cloud.changlu.agentforge.agent.extend.middlewares.compact;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactEvent;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactTrigger;

/**
 * 上下文压缩生命周期钩子。
 *
 * <p>业务层可由自定义 Compact Middleware 实现该接口，在压缩开始前及成功或失败结束后扩展监控、事件推送等行为。
 */
public interface CompactLifecycleHook {

    /**
     * 实际压缩或工具结果截断开始前执行。
     *
     * @param currentStep 当前 Agent 步骤；工具结果触发时为 -1
     * @param trigger 压缩触发来源
     * @param beforeMessages 压缩前消息数量
     * @param beforeTokens 压缩前估算 Token 数
     * @param maxContextTokens 当前模型最大上下文 Token 数
     * @param chatContext Agent 对话上下文
     */
    default void beforeCompact(
            int currentStep,
            CompactTrigger trigger,
            int beforeMessages,
            int beforeTokens,
            int maxContextTokens,
            AgentChatContext chatContext) {}

    /**
     * 压缩结束后执行，成功与失败都会触发。
     *
     * @param event 包含成功状态、前后消息数、前后 Token、耗时及错误信息的结果事件
     * @param chatContext Agent 对话上下文
     */
    default void afterCompact(CompactEvent event, AgentChatContext chatContext) {}
}
