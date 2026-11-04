package cloud.changlu.agentforge.agent.extend.middlewares.append;

import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;

import java.util.List;
import java.util.stream.Collectors;

/** 在下一次模型调用边界，将运行期追加消息注入 Agent Memory 和 ChatRequest。 */
public class UserMessageAppendMiddleware implements IAgentMiddleware {
    private final IUserMessageAppendService appendService;
    private final UserMessageAppendEventListener eventListener;

    public UserMessageAppendMiddleware(
            IUserMessageAppendService appendService, UserMessageAppendEventListener eventListener) {
        this.appendService = appendService;
        this.eventListener = eventListener;
    }

    @Override
    public void onInitComplete(AgentChatContext context) {
        appendService.open(context.getMemoryId());
    }

    @Override
    public ChatRequest beforeModelCall(
            int currentStep, ChatRequest request, AgentChatContext context) {
        List<AppendedUserMessage> messages = appendService.drain(context.getMemoryId());
        if (messages.isEmpty()) return request;
        context.getChatMemory().add(UserMessage.from(mergeMessages(messages)));
        ChatRequest rebuilt =
                ChatRequest.builder()
                        .messages(context.getChatMemory().messages())
                        .parameters(request.parameters())
                        .build();
        for (AppendedUserMessage message : messages) {
            eventListener.effective(context.getMemoryId(), message, currentStep);
        }
        return rebuilt;
    }

    /** 把同一调用边界收到的追加内容合并为一条用户消息，并向模型明确其运行期补充语义。 */
    private String mergeMessages(List<AppendedUserMessage> messages) {
        return messages.stream()
                .map(message -> "- " + message.getContent())
                .collect(
                        Collectors.joining(
                                "\n",
                                "The user added the following instructions during the current Agent run:\n",
                                ""));
    }

    @Override
    public void onStop(int currentStep, StopResult result, AgentChatContext context) {
        close(context);
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext context) {
        close(context);
    }

    private void close(AgentChatContext context) {
        for (AppendedUserMessage message : appendService.closeAndDrain(context.getMemoryId())) {
            eventListener.deferred(context.getMemoryId(), message);
        }
    }
}
