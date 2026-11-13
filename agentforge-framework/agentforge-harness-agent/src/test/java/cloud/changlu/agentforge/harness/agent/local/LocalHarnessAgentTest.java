package cloud.changlu.agentforge.harness.agent.local;

import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.extend.middlewares.LoggingIAgentMiddleware;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.ChatResultState;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import cloud.changlu.agentforge.harness.runtime.RuntimeType;
import cloud.changlu.agentforge.harness.runtime.local.LocalRuntime;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.tool.LocalRuntimeTools;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.FinishReason;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Local runtime scenario tests covering HarnessAgent assembly rather than individual tools. */
public class LocalHarnessAgentTest {

    private static final String BASE_URL_ENV = "AGENTFORGE_HARNESS_BASE_URL";
    private static final String MODEL_ENV = "AGENTFORGE_HARNESS_MODEL";
    private static final String API_KEY_ENV = "AGENTFORGE_HARNESS_API_KEY";

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void buildsLocalAgentWithDefaultsWorkspaceAndLoggingMiddleware() throws Exception {
        Workspace workspace = workspace("offline-workspace");
        ChatModel model =
                request ->
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from("local harness ready"))
                                .finishReason(FinishReason.STOP)
                                .build();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .runtime(LocalRuntime.builder().workspace(workspace).build())
                        .chatModel(model)
                        .chatMemoryProvider(new MessageWindowChatMemoryProvider(20))
                        .middleware(new LoggingIAgentMiddleware())
                        .build();
        try {
            ChatResult result =
                    agent.run(
                            AgentRequest.builder()
                                    .memoryId("local-offline-session")
                                    .question("confirm local harness")
                                    .build());

            assertEquals(ChatResultState.FINISHED, result.getChatResultState());
            assertEquals("local harness ready", result.getRes());
            assertEquals(RuntimeType.LOCAL, agent.runtimeType());
            assertTrue(agent.runtimeOptions() instanceof LocalRuntimeOptions);
            assertTrue(agent.runtimeTools() instanceof LocalRuntimeTools);
            assertEquals(workspace.root(), agent.workspace().root());
            assertTrue(agent.systemPrompt().contains("# Harness Agent"));
            assertTrue(agent.systemPrompt().contains("software engineering Harness Agent"));
            assertTrue(agent.systemPrompt().contains("# Runtime"));
            assertTrue(agent.systemPrompt().contains("offline-workspace"));
            assertTrue(agent.systemPrompt().contains(workspace.root().toString()));
        } finally {
            agent.close();
        }
    }

    @Test
    public void preservesUserProvidedSystemPrompt() throws Exception {
        Workspace workspace = workspace("custom-prompt-workspace");
        ChatModel model =
                request ->
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from("ok"))
                                .finishReason(FinishReason.STOP)
                                .build();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .runtime(LocalRuntime.builder().workspace(workspace).build())
                        .systemPrompt("custom harness prompt")
                        .chatModel(model)
                        .chatMemoryProvider(new MessageWindowChatMemoryProvider(20))
                        .build();
        try {
            assertTrue(agent.systemPrompt().startsWith("custom harness prompt"));
            assertTrue(!agent.systemPrompt().contains("software engineering Harness Agent"));
            assertTrue(!agent.systemPrompt().contains("custom-prompt-workspace"));
        } finally {
            agent.close();
        }
    }

    @Test
    public void requiresRuntime() throws Exception {
        try {
            HarnessAgent.builder().build();
            fail("runtime must be required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("runtime"));
        }
    }

    /** Optional OpenAI-compatible live verification; credentials are read only from environment. */
    @Test
    public void runsLocalAgentAgainstEnvironmentConfiguredModel() throws Exception {
        String baseUrl = System.getenv(BASE_URL_ENV);
        String modelName = System.getenv(MODEL_ENV);
        String apiKey = System.getenv(API_KEY_ENV);
        if (isBlank(baseUrl) || isBlank(modelName) || isBlank(apiKey)) {
            System.out.println(
                    "Skip local HarnessAgent live test: set "
                            + BASE_URL_ENV
                            + ", "
                            + MODEL_ENV
                            + " and "
                            + API_KEY_ENV
                            + ".");
            return;
        }

        OpenAiChatModel model =
                OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .apiKey(apiKey)
                        .temperature(0.0)
                        .maxTokens(64)
                        .build();
        HarnessAgent agent =
                HarnessAgent.builder()
                        .runtime(
                                LocalRuntime.builder()
                                        .workspace(workspace("live-workspace"))
                                        .options(LocalRuntimeOptions.defaults())
                                        .build())
                        .chatModel(model)
                        .chatMemoryProvider(new MessageWindowChatMemoryProvider(20))
                        .middleware(new LoggingIAgentMiddleware())
                        .build();
        try {
            ChatResult result =
                    agent.run(
                            AgentRequest.builder()
                                    .memoryId("local-live-session")
                                    .question("Reply with exactly: harness-ready")
                                    .build());
            assertEquals(ChatResultState.FINISHED, result.getChatResultState());
            assertTrue(result.getRes() != null && !result.getRes().trim().isEmpty());
        } finally {
            agent.close();
        }
    }

    private Workspace workspace(String name) throws Exception {
        return Workspace.builder().id(name).root(temporaryFolder.newFolder(name).toPath()).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
