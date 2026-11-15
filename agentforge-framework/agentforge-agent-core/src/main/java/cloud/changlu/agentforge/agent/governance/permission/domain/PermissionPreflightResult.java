package cloud.changlu.agentforge.agent.governance.permission.domain;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 Tool Call 批次预检结果：可能直接放行、直接拒绝若干工具，或冻结为中断批次。
 *
 * <p>DENY 与 ASK 互斥：DENY 工具不进入中断，只生成拒绝 Tool Result；ASK/ASK_USER 才冻结批次等待响应。
 */
public final class PermissionPreflightResult {
    private final AgentSuspendInfo suspendInfo;
    private final List<PermissionDenial> denials;

    private PermissionPreflightResult(
            AgentSuspendInfo suspendInfo, List<PermissionDenial> denials) {
        this.suspendInfo = suspendInfo;
        this.denials =
                denials == null
                        ? Collections.<PermissionDenial>emptyList()
                        : Collections.unmodifiableList(new ArrayList<PermissionDenial>(denials));
    }

    public static PermissionPreflightResult allowed() {
        return new PermissionPreflightResult(null, Collections.<PermissionDenial>emptyList());
    }

    public static PermissionPreflightResult suspended(
            AgentSuspendInfo suspendInfo, List<PermissionDenial> denials) {
        return new PermissionPreflightResult(suspendInfo, denials);
    }

    public static PermissionPreflightResult denied(List<PermissionDenial> denials) {
        return new PermissionPreflightResult(null, denials);
    }

    public boolean isSuspended() {
        return suspendInfo != null;
    }

    public boolean hasDenials() {
        return !denials.isEmpty();
    }

    public boolean isEmpty() {
        return suspendInfo == null && denials.isEmpty();
    }

    public AgentSuspendInfo getSuspendInfo() {
        return suspendInfo;
    }

    public List<PermissionDenial> getDenials() {
        return denials;
    }
}
