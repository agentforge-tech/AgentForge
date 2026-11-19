package cloud.changlu.agentforge.harness.agent.local;

import cloud.changlu.agentforge.agent.IInterruptibleAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.governance.PermissionInterruptModule;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.ChatResultState;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import cloud.changlu.agentforge.harness.runtime.local.LocalRuntime;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.FinishReason;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 验证 HarnessAgent 注入的权限模块会透传给 ReActAgent，并可直接复用中断恢复能力。 */
public class HarnessAgentInterruptTest {

    private static final Object MEMORY_ID = "harness-interrupt-session";

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void passesInjectedModuleThroughToReActAgent() throws Exception {
        Workspace workspace =
                Workspace.builder()
                        .id("harness-interrupt-workspace")
                        .root(temporaryFolder.newFolder("harness-interrupt-workspace").toPath())
                        .build();
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("恢复后继续完成");
        ChatMemoryProvider memoryProvider = new MessageWindowChatMemoryProvider(100);
        PermissionInterruptModule module =
                PermissionInterruptModule.safeDefaults().ask("getWeather", ".*").build();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .runtime(LocalRuntime.builder().workspace(workspace).build())
                        .chatModel(chatModel)
                        .chatMemoryProvider(memoryProvider)
                        .additionalTool(new WeatherTools())
                        .permissionInterruptModule(module)
                        .build();
        try {
            assertTrue(agent instanceof IInterruptibleAgent);

            ChatResult suspended =
                    agent.run(
                            AgentRequest.builder()
                                    .memoryId(MEMORY_ID)
                                    .executionId("harness-interrupt-execution")
                                    .question("杭州天气怎么样？")
                                    .build());

            assertEquals(ChatResultState.SUSPENDED, suspended.getChatResultState());
            assertEquals(AgentRunState.WAITING_INTERRUPT, suspended.getRunState());
            assertNotNull(suspended.getSuspendInfo());
            InterruptRequest interrupt = suspended.getSuspendInfo().getInterrupts().get(0);
            assertEquals(InterruptType.PERMISSION_APPROVAL, interrupt.getType());

            ChatResult resumed =
                    agent.resumeInterrupt(
                            new InterruptBatchResumeCommand(
                                    suspended.getSuspendInfo().getBatchId(),
                                    Collections.singletonList(
                                            new InterruptResponse(
                                                    interrupt.getInterruptId(),
                                                    ApprovalAction.APPROVE_ONCE,
                                                    null))));

            assertEquals(ChatResultState.FINISHED, resumed.getChatResultState());
            assertEquals(AgentRunState.SUCCESS, resumed.getRunState());
            assertEquals("恢复后继续完成", resumed.getRes());

            List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
            ChatMessage toolResult = messages.get(messages.size() - 2);
            assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, toolResult.type());
            assertTrue(toolResult.text().contains("22 degrees Celsius"));
        } finally {
            agent.close();
        }
    }

    private static final class WeatherTools {

        @Tool(name = "getWeather", value = "Returns the weather for the given city")
        public String getWeather(@P("The city name") String city) {
            return "The weather in " + city + " is 22 degrees Celsius and sunny.";
        }
    }

    private static final class ScriptedChatModel implements ChatModel {

        private final Deque<ChatResponse> responses = new ArrayDeque<ChatResponse>();

        ScriptedChatModel enqueueToolCall(String id, String name, String arguments) {
            responses.add(
                    ChatResponse.builder()
                            .aiMessage(
                                    AiMessage.from(
                                            Collections.singletonList(
                                                    ToolExecutionRequest.from(
                                                            id, name, arguments))))
                            .finishReason(FinishReason.TOOL_EXECUTION)
                            .build());
            return this;
        }

        ScriptedChatModel enqueueText(String text) {
            responses.add(
                    ChatResponse.builder()
                            .aiMessage(AiMessage.from(text))
                            .finishReason(FinishReason.STOP)
                            .build());
            return this;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            ChatResponse response = responses.pollFirst();
            if (response == null) {
                throw new IllegalStateException("no scripted response left");
            }
            return response;
        }
    }
}
