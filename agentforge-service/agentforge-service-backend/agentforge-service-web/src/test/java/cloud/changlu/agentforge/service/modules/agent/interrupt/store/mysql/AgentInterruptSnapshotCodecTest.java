package cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskQuestion;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswerType;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserOption;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentInterruptSnapshotCodecTest {

    private final AgentInterruptSnapshotCodec codec =
            new AgentInterruptSnapshotCodec(new ObjectMapper());

    @Test
    public void shouldRoundTripAskUserSnapshot() {
        ToolExecutionRequest request =
                ToolExecutionRequest.from("call_07", "ask_user", "{\"questions\":[]}");
        AskUserOption option = new AskUserOption("test", "测试", null);
        AskQuestion question =
                new AskQuestion(
                        "env",
                        "请选择部署环境",
                        null,
                        AskUserAnswerType.SINGLE_CHOICE,
                        true,
                        Collections.singletonList(option),
                        null,
                        null,
                        null,
                        null);
        AskUserRequest askUser =
                new AskUserRequest(
                        "call_07",
                        "ask_user",
                        Collections.singletonList(question),
                        "发布前需要确认",
                        "继续发布");
        InterruptRequest interrupt =
                new InterruptRequest("int_1", InterruptType.ASK_USER, request, askUser);
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        "batch_1",
                        "session-1",
                        "exec_1",
                        3,
                        Collections.singletonList(request),
                        Collections.singletonList(interrupt));

        AgentInterruptSnapshot decoded = codec.decode(pojo(codec.encode(snapshot)));

        assertEquals("batch_1", decoded.getBatchId());
        assertEquals("session-1", decoded.getMemoryId());
        assertEquals(1, decoded.getToolRequests().size());
        assertEquals(request, decoded.getToolRequests().get(0));
        assertEquals(1, decoded.getInterrupts().size());
        assertEquals(InterruptType.ASK_USER, decoded.getInterrupts().get(0).getType());
        AskUserRequest decodedAskUser = decoded.getInterrupts().get(0).getAskUserRequest();
        assertEquals("call_07", decodedAskUser.getToolCallId());
        assertEquals("发布前需要确认", decodedAskUser.getReason());
        AskQuestion decodedQuestion = decodedAskUser.getQuestions().get(0);
        assertEquals("env", decodedQuestion.getId());
        assertEquals(AskUserAnswerType.SINGLE_CHOICE, decodedQuestion.getType());
        assertTrue(decodedQuestion.isRequired());
        assertEquals("test", decodedQuestion.getOptions().get(0).getId());
        assertEquals("测试", decodedQuestion.getOptions().get(0).getLabel());
        assertTrue(decoded.getDenials().isEmpty());
    }

    @Test
    public void shouldRoundTripPermissionApprovalAndDenial() {
        ToolExecutionRequest approvalRequest =
                ToolExecutionRequest.from("call_01", "shell", "{\"command\":\"ls\"}");
        ToolExecutionRequest deniedRequest =
                ToolExecutionRequest.from("call_02", "shell", "{\"command\":\"rm -rf demo\"}");
        InterruptRequest interrupt =
                new InterruptRequest(
                        "int_1", InterruptType.PERMISSION_APPROVAL, approvalRequest, null);
        PermissionDenial denial =
                new PermissionDenial(deniedRequest, "tool call denied by permission policy: shell");
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        "batch_2",
                        "session-1",
                        "exec_1",
                        5,
                        Arrays.asList(approvalRequest, deniedRequest),
                        Collections.singletonList(interrupt),
                        Collections.singletonList(denial));

        AgentInterruptSnapshot decoded = codec.decode(pojo(codec.encode(snapshot)));

        assertNull(decoded.getInterrupts().get(0).getPayload());
        assertEquals(1, decoded.getDenials().size());
        assertEquals(deniedRequest, decoded.getDenials().get(0).getRequest());
        assertEquals(
                "tool call denied by permission policy: shell",
                decoded.getDenials().get(0).getReason());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldRejectSchemaVersionMismatch() {
        ToolExecutionRequest request =
                ToolExecutionRequest.from("call_01", "shell", "{\"command\":\"ls\"}");
        InterruptRequest interrupt =
                new InterruptRequest("int_1", InterruptType.PERMISSION_APPROVAL, request, null);
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        "batch_3",
                        "session-1",
                        "exec_1",
                        1,
                        Collections.singletonList(request),
                        Collections.singletonList(interrupt));
        AgentInterruptSnapshotPojo pojo = pojo(codec.encode(snapshot));
        pojo.setSchemaVersion(2);

        codec.decode(pojo);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNonStringMemoryId() {
        codec.memoryId(1L);
    }

    private static AgentInterruptSnapshotPojo pojo(String json) {
        AgentInterruptSnapshotPojo pojo = new AgentInterruptSnapshotPojo();
        pojo.setBatchId("batch_1");
        pojo.setMemoryId("session-1");
        pojo.setSourceExecutionId("exec_1");
        pojo.setStep(3);
        pojo.setStatus("PENDING");
        pojo.setSchemaVersion(1);
        pojo.setSnapshotJson(json);
        return pojo;
    }
}
