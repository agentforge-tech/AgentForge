package cloud.changlu.agentforge.service.modules.session.enums;

import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.step.StopResultState;

/** 框架运行状态到会话消息状态的统一映射，避免中间件直接写数字。 */
public final class SessionStatusTransfer {

    private SessionStatusTransfer() {}

    /** 停止结果映射为消息状态。 */
    public static SessionMessageStatusEnum fromStopResult(StopResultState state) {
        if (state == null) {
            return SessionMessageStatusEnum.FAILED;
        }
        switch (state) {
            case NORMAL:
                return SessionMessageStatusEnum.SUCCEEDED;
            case CANCEL:
                return SessionMessageStatusEnum.CANCELLED;
            case MAX_STEPS:
                return SessionMessageStatusEnum.FAILED;
            default:
                return SessionMessageStatusEnum.FAILED;
        }
    }

    /** Agent 运行状态映射为消息状态。 */
    public static SessionMessageStatusEnum fromRunState(AgentRunState state) {
        if (state == null) {
            return SessionMessageStatusEnum.FAILED;
        }
        switch (state) {
            case SUCCESS:
                return SessionMessageStatusEnum.SUCCEEDED;
            case CANCEL:
                return SessionMessageStatusEnum.CANCELLED;
            case MODEL_CALL_ERROR:
            case MAX_STEPS:
                return SessionMessageStatusEnum.FAILED;
            default:
                return SessionMessageStatusEnum.FAILED;
        }
    }

    /** 消息状态是否终态。 */
    public static boolean isTerminal(SessionMessageStatusEnum status) {
        return status == SessionMessageStatusEnum.SUCCEEDED
                || status == SessionMessageStatusEnum.FAILED
                || status == SessionMessageStatusEnum.CANCELLED;
    }
}
