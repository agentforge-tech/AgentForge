package cloud.changlu.agentforge.agent.governance.interrupt.domain;

import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次中断恢复的结构化视图，供中间件同步感知审批决定和 AskUser 答案。 */
public final class InterruptResumeContext {
    private final AgentInterruptSnapshot snapshot;
    private final List<InterruptResponse> responses;
    private final Map<String, ApprovalAction> approvalActions;
    private final Map<String, AskUserResponse> askUserAnswers;

    public InterruptResumeContext(
            AgentInterruptSnapshot snapshot, List<InterruptResponse> responses) {
        this.snapshot = snapshot;
        this.responses =
                responses == null
                        ? Collections.<InterruptResponse>emptyList()
                        : Collections.unmodifiableList(new ArrayList<InterruptResponse>(responses));
        Map<String, ApprovalAction> actions = new LinkedHashMap<String, ApprovalAction>();
        Map<String, AskUserResponse> answers = new LinkedHashMap<String, AskUserResponse>();
        Map<String, InterruptResponse> indexed = new LinkedHashMap<String, InterruptResponse>();
        for (InterruptResponse response : this.responses) {
            indexed.put(response.getInterruptId(), response);
        }
        for (InterruptRequest interrupt : snapshot.getInterrupts()) {
            InterruptResponse response = indexed.get(interrupt.getInterruptId());
            if (response == null) continue;
            if (interrupt.getType() == InterruptType.PERMISSION_APPROVAL
                    && response.getAction() != null) {
                actions.put(interrupt.getInterruptId(), response.getAction());
            } else if (interrupt.getType() == InterruptType.ASK_USER) {
                answers.put(interrupt.getInterruptId(), response.getAskUserResponse());
            }
        }
        this.approvalActions =
                Collections.unmodifiableMap(new LinkedHashMap<String, ApprovalAction>(actions));
        this.askUserAnswers =
                Collections.unmodifiableMap(new LinkedHashMap<String, AskUserResponse>(answers));
    }

    public String getBatchId() {
        return snapshot.getBatchId();
    }

    public AgentInterruptSnapshot getSnapshot() {
        return snapshot;
    }

    public Object getMemoryId() {
        return snapshot.getMemoryId();
    }

    public String getSourceExecutionId() {
        return snapshot.getSourceExecutionId();
    }

    public int getStep() {
        return snapshot.getStep();
    }

    public List<ToolExecutionRequest> getToolRequests() {
        return snapshot.getToolRequests();
    }

    public List<InterruptRequest> getInterrupts() {
        return snapshot.getInterrupts();
    }

    public List<InterruptResponse> getResponses() {
        return responses;
    }

    public InterruptResponse responseOf(String interruptId) {
        for (InterruptResponse response : responses) {
            if (response.getInterruptId().equals(interruptId)) return response;
        }
        return null;
    }

    public ApprovalAction approvalActionOf(String interruptId) {
        return approvalActions.get(interruptId);
    }

    public AskUserResponse askUserAnswerOf(String interruptId) {
        return askUserAnswers.get(interruptId);
    }

    public Map<String, ApprovalAction> getApprovalActions() {
        return approvalActions;
    }

    public Map<String, AskUserResponse> getAskUserAnswers() {
        return askUserAnswers;
    }
}
