package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.governance.tool.askuser.AskQuestion;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswer;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswerType;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserRequest;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserSupport;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AskUserSupportTest {

    @Test
    public void shouldParseBatchQuestionsAndAliases() {
        AskUserRequest request =
                AskUserSupport.parse(
                        tool(
                                "{\"questions\":["
                                        + "{\"id\":\"env\",\"question\":\"选择环境\",\"type\":\"radio\","
                                        + "\"options\":[{\"id\":\"test\",\"label\":\"测试\"},{\"id\":\"prod\",\"value\":\"生产\"}]},"
                                        + "{\"id\":\"note\",\"header\":\"补充说明\",\"type\":\"input\",\"required\":false}"
                                        + "],\"reason\":\"发布确认\",\"submitLabel\":\"开始\"}"));

        assertEquals(2, request.getQuestions().size());
        assertEquals("发布确认", request.getReason());
        assertEquals("开始", request.getSubmitLabel());
        AskQuestion env = request.getQuestions().get(0);
        assertEquals(AskUserAnswerType.SINGLE_CHOICE, env.getType());
        assertEquals("选择环境", env.getTitle());
        assertEquals("prod", env.getOptions().get(1).getId());
        assertEquals("生产", env.getOptions().get(1).getLabel());
        AskQuestion note = request.getQuestions().get(1);
        assertEquals(AskUserAnswerType.TEXT, note.getType());
        assertEquals("补充说明", note.getTitle());
    }

    @Test
    public void shouldRenderLegacyQuestionWithStructuredAnswer() {
        AskUserRequest request = AskUserSupport.parse(tool("{\"question\":\"是否继续？\"}"));
        AskUserResponse response = AskUserResponse.text("q1", "继续");
        AskUserSupport.validate(request, response);

        String result = AskUserSupport.renderToolResult(request, response, "int_1");
        assertTrue(result.contains("\"status\":\"answered\""));
        assertTrue(result.contains("\"interactionId\":\"int_1\""));
        assertTrue(result.contains("\"questionId\":\"q1\""));
        assertTrue(result.contains("\"customValue\":\"继续\""));
    }

    @Test
    public void shouldAcceptMultipleChoiceWithinRange() {
        AskUserRequest request =
                AskUserSupport.parse(
                        tool(
                                "{\"questions\":[{\"id\":\"mods\",\"title\":\"选择模块\","
                                        + "\"type\":\"multiple_choice\",\"minSelections\":1,\"maxSelections\":2,"
                                        + "\"options\":[{\"id\":\"api\",\"label\":\"API\"},"
                                        + "{\"id\":\"web\",\"label\":\"Web\"},{\"id\":\"job\",\"label\":\"Job\"}]}]}"));

        AskUserResponse response =
                AskUserResponse.of(AskUserAnswer.multiple("mods", Arrays.asList("api", "web")));

        AskUserSupport.validate(request, response);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMultipleChoiceOutOfRange() {
        AskUserRequest request =
                AskUserSupport.parse(
                        tool(
                                "{\"questions\":[{\"id\":\"mods\",\"title\":\"选择模块\","
                                        + "\"type\":\"multiple_choice\",\"minSelections\":1,\"maxSelections\":1,"
                                        + "\"options\":[{\"id\":\"api\",\"label\":\"API\"},"
                                        + "{\"id\":\"web\",\"label\":\"Web\"}]}]}"));

        AskUserSupport.validate(
                request,
                AskUserResponse.of(AskUserAnswer.multiple("mods", Arrays.asList("api", "web"))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMissingRequiredQuestion() {
        AskUserRequest request =
                AskUserSupport.parse(
                        tool(
                                "{\"questions\":[{\"id\":\"env\",\"title\":\"选择环境\","
                                        + "\"type\":\"single_choice\","
                                        + "\"options\":[{\"id\":\"test\",\"label\":\"测试\"},"
                                        + "{\"id\":\"prod\",\"label\":\"生产\"}]}]}"));

        AskUserSupport.validate(request, new AskUserResponse(null));
    }

    private static ToolExecutionRequest tool(String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("ask_user")
                .arguments(arguments)
                .build();
    }
}
