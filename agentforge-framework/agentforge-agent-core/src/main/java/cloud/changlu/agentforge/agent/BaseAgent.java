package cloud.changlu.agentforge.agent;

import cloud.changlu.agentforge.agent.component.state.control.AgentRunControl;
import cloud.changlu.agentforge.agent.component.state.control.AgentRunRegistry;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.exception.AgentException;
import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.UserMessage;

import java.util.Optional;
import java.util.UUID;

/**
 * @description 抽象 计划 -> 思考 + 行动 执行Agent
 * @author changlu
 * @date 2026/9/16
 */
public abstract class BaseAgent implements IAgent {

    protected final AgentRunRegistry runRegistry = new AgentRunRegistry();

    protected abstract ChatModel getChatModel();

    // 包含了ChatMemory
    protected abstract ChatMemoryProvider getChatMemoryProvider();

    /** 初始化本次运行的上下文：绑定会话记忆 + 写入本轮用户消息 */
    protected AgentChatContext init(AgentRequest request) {
        AgentChatContext chatContext = initParams(request);

        initMessages(chatContext);

        return chatContext;
    }

    protected AgentChatContext initParams(AgentRequest request) {
        ChatMemory chatMemory = getChatMemoryProvider().get(request.getMemoryId());
        String executionId = request.getExecutionId();
        if (executionId == null || executionId.trim().isEmpty()) {
            executionId = createExecutionId();
            request.setExecutionId(executionId);
        }

        return AgentChatContext.builder()
                .executionId(executionId)
                .request(request)
                .chatModel(getChatModel())
                .chatMemory(chatMemory)
                .extensions(
                        request.getExtensions() == null
                                ? new java.util.HashMap<String, Object>()
                                : new java.util.HashMap<String, Object>(request.getExtensions()))
                .build();
    }

    /** 初始化消息 */
    protected void initMessages(AgentChatContext chatContext) {
        addMessage(chatContext, buildUserMessage(chatContext));
    }

    /**
     * 构建用户message
     *
     * @param chatContext AgentChatContext
     * @return UserMessage
     */
    protected UserMessage buildUserMessage(AgentChatContext chatContext) {
        String question = chatContext.getQuestion();
        return UserMessage.from(
                question == null || question.trim().isEmpty() ? "请结合上下文回答。" : question.trim());
    }

    protected void addMessage(AgentChatContext chatContext, ChatMessage newMessages) {
        ChatMemory chatMemory = chatContext.getChatMemory();
        chatMemory.add(newMessages);
    }

    /**
     * 恢复执行前准备已存在的消息：默认不做处理，由具体 Agent 决定是否刷新系统消息等。
     *
     * @param chatContext 已绑定会话记忆的上下文
     */
    protected void prepareMessagesForResume(AgentChatContext chatContext) {}

    @Override
    public boolean cancel(Object memoryId, String executionId) {
        return runRegistry.cancel(memoryId, executionId, "任务已被用户取消");
    }

    @Override
    public String createExecutionId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public Optional<AgentExecutionState> getExecutionState(Object memoryId, String executionId) {
        return runRegistry.snapshot(memoryId, executionId);
    }

    protected AgentRunControl registerRun(AgentChatContext context) {
        return runRegistry.register(context.getExecutionId(), context.getMemoryId());
    }

    protected AgentRunControl runControl(AgentChatContext context) {
        AgentRunControl control = runRegistry.current(context.getMemoryId());
        return control != null && control.getExecutionId().equals(context.getExecutionId())
                ? control
                : null;
    }

    @Override
    public boolean clearContext(Object memoryId) {
        ChatMemoryProvider chatMemoryProvider = getChatMemoryProvider();
        if (chatMemoryProvider == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
        ChatMemory chatMemory = chatMemoryProvider.get(memoryId);
        chatMemory.clear();
        runRegistry.clear(memoryId);
        return true;
    }
}
