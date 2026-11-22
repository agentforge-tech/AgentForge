package cloud.changlu.agentforge.agent.extend.middlewares.read.middleware;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.ImageLoader;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResourceKind;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.support.ReadResultJson;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadResourceTool;
import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.model.chat.message.Content;
import cloud.changlu.agentforge.model.chat.message.ImageContent;
import cloud.changlu.agentforge.model.chat.message.TextContent;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReadArtifactMiddlewareTest {

    @Test
    public void shouldCollectToolResultAndBuildUserMessage() {
        ChatMemory memory = new MessageWindowChatMemoryProvider(100).get("m-1");
        AgentChatContext context =
                AgentChatContext.builder()
                        .executionId("e-1")
                        .request(
                                AgentRequest.builder()
                                        .memoryId("m-1")
                                        .executionId("e-1")
                                        .question("q")
                                        .build())
                        .chatMemory(memory)
                        .build();
        context.putExtension("attachment.supportsImage", true);

        ReadResult image = new ReadResult();
        image.setKind(ReadResourceKind.IMAGE);
        image.setUrl("http://127.0.0.1:8080/upload/a.png");
        image.setName("a.png");
        image.setMimeType("image/png");
        image.setFileType("png");

        ReadBatchResult batch = new ReadBatchResult();
        batch.setResults(Collections.singletonList(image));

        ReadArtifactMiddleware middleware =
                ReadArtifactMiddleware.builder()
                        .imageLoader(
                                url ->
                                        new ImageLoader.ImageBinary(
                                                new byte[] {1, 2, 3}, "image/png"))
                        .build();
        middleware.afterToolExecution(
                ToolExecutionRequest.from("c1", ReadResourceTool.TOOL_NAME, "{}"),
                ReadResultJson.toJson(batch),
                context);

        ChatRequest request =
                middleware.beforeModelCall(
                        1, ChatRequest.builder().message(UserMessage.from("q")).build(), context);

        assertEquals(1, request.messages().size());
        assertTrue(request.messages().get(0) instanceof UserMessage);
        UserMessage userMessage = (UserMessage) request.messages().get(0);
        assertEquals(2, userMessage.contents().size());

        Content text = userMessage.contents().get(0);
        Content imageContent = userMessage.contents().get(1);
        assertTrue(text instanceof TextContent);
        assertTrue(imageContent instanceof ImageContent);
        assertTrue(((ImageContent) imageContent).isInline());
    }
}
