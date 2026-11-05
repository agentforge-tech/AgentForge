package cloud.changlu.agentforge.agent.extend.middlewares.compact;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemory;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactEvent;
import cloud.changlu.agentforge.agent.extend.middlewares.compact.domain.CompactTrigger;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CompactSummarizationMiddlewareHookTest {

    @Test
    public void shouldInvokeBusinessHooksWithCompactMetrics() {
        RecordingCompactMiddleware middleware =
                new RecordingCompactMiddleware(
                        CompactSummarizationMiddleware.CompactConfig.builder()
                                .maxContextTokens(200)
                                .compressThreshold(50)
                                .build());
        AgentChatContext context =
                AgentChatContext.builder()
                        .request(
                                AgentRequest.builder()
                                        .memoryId("hook-session")
                                        .question("test")
                                        .build())
                        .chatMemory(MessageWindowChatMemory.withMaxMessages(100))
                        .build();
        char[] chars = new char[2000];
        Arrays.fill(chars, 'x');

        String result =
                middleware.afterToolExecution(
                        ToolExecutionRequest.from("call-1", "large_tool", "{}"),
                        new String(chars),
                        context);

        assertTrue(result.contains("工具执行结果已截断"));
        assertEquals(CompactTrigger.TOOL_RESULT, middleware.beforeTrigger);
        assertTrue(middleware.beforeMessages > 0);
        assertTrue(middleware.beforeTokens > 0);
        assertEquals(200, middleware.maxContextTokens);
        assertNotNull(middleware.afterEvent);
        assertTrue(middleware.afterEvent.isSuccess());
        assertEquals(CompactTrigger.TOOL_RESULT, middleware.afterEvent.getTrigger());
    }

    private static final class RecordingCompactMiddleware extends CompactSummarizationMiddleware {
        private CompactTrigger beforeTrigger;
        private int beforeMessages;
        private int beforeTokens;
        private int maxContextTokens;
        private CompactEvent afterEvent;

        private RecordingCompactMiddleware(CompactConfig config) {
            super(config);
        }

        @Override
        public void beforeCompact(
                int currentStep,
                CompactTrigger trigger,
                int beforeMessages,
                int beforeTokens,
                int maxContextTokens,
                AgentChatContext chatContext) {
            this.beforeTrigger = trigger;
            this.beforeMessages = beforeMessages;
            this.beforeTokens = beforeTokens;
            this.maxContextTokens = maxContextTokens;
        }

        @Override
        public void afterCompact(CompactEvent event, AgentChatContext chatContext) {
            this.afterEvent = event;
        }
    }
}
