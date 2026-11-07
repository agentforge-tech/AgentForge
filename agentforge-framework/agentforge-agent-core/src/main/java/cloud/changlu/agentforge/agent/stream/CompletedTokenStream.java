package cloud.changlu.agentforge.agent.stream;

import cloud.changlu.agentforge.agent.exception.AgentException;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.FinishReason;
import cloud.changlu.agentforge.model.tool.execution.ToolExecution;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 已经结束的恢复场景：start() 时直接把既有回答作为完成事件下发。 */
public final class CompletedTokenStream implements TokenStream {

    private final String executionId;
    private final ChatResponse response;

    private Consumer<ChatResponse> completeResponseHandler;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public CompletedTokenStream(String executionId, String text) {
        this.executionId = executionId;
        this.response =
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(text))
                        .finishReason(FinishReason.STOP)
                        .build();
    }

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
        return this;
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> partialThinkingHandler) {
        return this;
    }

    @Override
    public TokenStream onIntermediateResponse(Consumer<ChatResponse> intermediateResponseHandler) {
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler) {
        this.completeResponseHandler = completeResponseHandler;
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> errorHandler) {
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        return this;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new AgentException("TokenStream已经启动，不能重复start");
        }
        if (completeResponseHandler != null) {
            completeResponseHandler.accept(response);
        }
    }
}
