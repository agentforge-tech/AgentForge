package cloud.changlu.agentforge.agent.governance.interrupt.domain;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 AI Call 中需要外部响应的冻结工具批次。
 *
 * <p>快照保存本轮完整 Tool Call 顺序、对应中断项、直接拒绝项和来源执行信息。
 *
 * <p>恢复时只消费该快照，不重新生成等待对象，也不改变原始 Tool Call 顺序。
 *
 * <p>状态生命周期：PENDING 等待响应，RESUMING 已被抢占，COMPLETED 已写回结果。
 *
 * <p>Store 实现应基于状态做原子 CAS，避免同一批次被重复消费。
 *
 * <p>对象不可变，状态推进通过 {@link #withStatus(InterruptBatchStatus)} 返回副本。
 *
 * <p>默认内存实现仅适用于单进程，集群场景应替换为持久化 Store。
 */
public final class AgentInterruptSnapshot {
    private final String batchId;
    private final Object memoryId;
    private final String sourceExecutionId;
    private final int step;
    private final InterruptBatchStatus status;
    private final List<ToolExecutionRequest> toolRequests;
    private final List<InterruptRequest> interrupts;
    private final List<PermissionDenial> denials;

    public AgentInterruptSnapshot(
            String batchId,
            Object memoryId,
            String sourceExecutionId,
            int step,
            List<ToolExecutionRequest> toolRequests,
            List<InterruptRequest> interrupts) {
        this(
                batchId,
                memoryId,
                sourceExecutionId,
                step,
                InterruptBatchStatus.PENDING,
                toolRequests,
                interrupts,
                Collections.<PermissionDenial>emptyList());
    }

    public AgentInterruptSnapshot(
            String batchId,
            Object memoryId,
            String sourceExecutionId,
            int step,
            InterruptBatchStatus status,
            List<ToolExecutionRequest> toolRequests,
            List<InterruptRequest> interrupts) {
        this(
                batchId,
                memoryId,
                sourceExecutionId,
                step,
                status,
                toolRequests,
                interrupts,
                Collections.<PermissionDenial>emptyList());
    }

    public AgentInterruptSnapshot(
            String batchId,
            Object memoryId,
            String sourceExecutionId,
            int step,
            InterruptBatchStatus status,
            List<ToolExecutionRequest> toolRequests,
            List<InterruptRequest> interrupts,
            List<PermissionDenial> denials) {
        this.batchId = batchId;
        this.memoryId = memoryId;
        this.sourceExecutionId = sourceExecutionId;
        this.step = step;
        this.status = status == null ? InterruptBatchStatus.PENDING : status;
        this.toolRequests =
                Collections.unmodifiableList(new ArrayList<ToolExecutionRequest>(toolRequests));
        this.interrupts = Collections.unmodifiableList(new ArrayList<InterruptRequest>(interrupts));
        this.denials =
                denials == null
                        ? Collections.<PermissionDenial>emptyList()
                        : Collections.unmodifiableList(new ArrayList<PermissionDenial>(denials));
    }

    public static AgentInterruptSnapshot initial(
            String batchId,
            Object memoryId,
            String sourceExecutionId,
            int step,
            List<ToolExecutionRequest> toolRequests,
            List<InterruptRequest> interrupts) {
        return initial(
                batchId,
                memoryId,
                sourceExecutionId,
                step,
                toolRequests,
                interrupts,
                Collections.<PermissionDenial>emptyList());
    }

    public static AgentInterruptSnapshot initial(
            String batchId,
            Object memoryId,
            String sourceExecutionId,
            int step,
            List<ToolExecutionRequest> toolRequests,
            List<InterruptRequest> interrupts,
            List<PermissionDenial> denials) {
        return new AgentInterruptSnapshot(
                batchId,
                memoryId,
                sourceExecutionId,
                step,
                InterruptBatchStatus.PENDING,
                toolRequests,
                interrupts,
                denials);
    }

    /** 返回状态推进后的副本，供 Store 以 CAS 方式替换。 */
    public AgentInterruptSnapshot withStatus(InterruptBatchStatus newStatus) {
        return new AgentInterruptSnapshot(
                batchId,
                memoryId,
                sourceExecutionId,
                step,
                newStatus,
                toolRequests,
                interrupts,
                denials);
    }

    public String getBatchId() {
        return batchId;
    }

    public Object getMemoryId() {
        return memoryId;
    }

    public String getSourceExecutionId() {
        return sourceExecutionId;
    }

    public int getStep() {
        return step;
    }

    public InterruptBatchStatus getStatus() {
        return status;
    }

    public List<ToolExecutionRequest> getToolRequests() {
        return toolRequests;
    }

    public List<InterruptRequest> getInterrupts() {
        return interrupts;
    }

    public List<PermissionDenial> getDenials() {
        return denials;
    }
}
