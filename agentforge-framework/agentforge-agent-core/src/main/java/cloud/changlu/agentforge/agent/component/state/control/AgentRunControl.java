package cloud.changlu.agentforge.agent.component.state.control;

import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionPhase;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionState;
import cloud.changlu.agentforge.agent.component.state.domain.AgentExecutionStatus;
import cloud.changlu.agentforge.agent.component.state.domain.RunningToolState;
import cloud.changlu.agentforge.agent.component.state.domain.ToolExecutionStatus;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 单次 Agent 执行的状态与主动取消控制中心。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>维护本次执行的 status/phase/step/tools 等运行状态；
 *   <li>登记当前模型调用句柄、工具/重试 Future 和执行线程，供取消时主动中断；
 *   <li>通过 {@link #requestCancel(String)} 受理取消，并在 {@link #finish} 中完成唯一一次终态收口。
 * </ul>
 *
 * <p>并发约定：
 *
 * <ul>
 *   <li>除 {@link #isTerminal()} 外，所有可变状态都由 {@code lock} 保护；
 *   <li>锁内只做状态赋值和句柄快照，不执行关闭连接、等待 Future、调用工具或用户回调；
 *   <li>{@code terminal} 是 CAS 闸门，保证正常完成、取消和异常只有一方能写入终态。
 * </ul>
 */
public final class AgentRunControl {

    private static final Logger log = Logger.getLogger(AgentRunControl.class.getName());

    private final String executionId;
    private final Object memoryId;

    /** 终态闸门：true 后不再允许状态回退或重复收口。 */
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    /** 保护下方所有可变字段；锁内禁止执行阻塞或外部回调。 */
    private final Object lock = new Object();

    private AgentExecutionStatus status = AgentExecutionStatus.CREATED;
    private AgentExecutionPhase phase = AgentExecutionPhase.INITIALIZING;
    private int currentStep;

    /** 当前工具批次，key 为 tool requestId；顺序与模型请求保持一致。 */
    private final Map<String, MutableToolState> tools =
            new LinkedHashMap<String, MutableToolState>();

    /** 已登记的工具执行、重试调度等异步任务，取消时统一 cancel(true)。 */
    private final List<Future<?>> futures = new ArrayList<Future<?>>();

    /** 当前模型调用的主动取消句柄（同步 HTTP call 或流式 SSE 连接）。 */
    private CancellationHandle modelHandle = CancellationHandle.NONE;

    /** 执行线程；用于 interrupt 兜底，不能替代句柄取消。 */
    private Thread executionThread;

    /** 取消受理后的回调，流式场景用于立即收口，不再等待模型回调。 */
    private Runnable cancelListener;

    private String cancelReason;
    private AgentRunState resultState;
    private String errorMessage;
    private long updatedAtMillis = System.currentTimeMillis();

    public AgentRunControl(String executionId, Object memoryId) {
        this.executionId = executionId;
        this.memoryId = memoryId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public Object getMemoryId() {
        return memoryId;
    }

    /** 是否已进入终态；终态后不再接受状态更新和重复收口。 */
    public boolean isTerminal() {
        return terminal.get();
    }

    /** 是否已受理取消：CANCEL_REQUESTED 表示正在收口，CANCELLED 表示已完成收口。 */
    public boolean isCancellationRequested() {
        synchronized (lock) {
            return isCancelledStatus();
        }
    }

    public String getCancelReason() {
        synchronized (lock) {
            return cancelReason;
        }
    }

    /** 进入某一轮循环的边界阶段；已取消或已终态时忽略。 */
    public void running(AgentExecutionPhase newPhase, int step) {
        synchronized (lock) {
            if (terminal.get() || isCancelledStatus()) return;
            status = AgentExecutionStatus.RUNNING;
            phase = newPhase;
            currentStep = step;
            touch();
        }
    }

    /** 标记开始请求模型；已取消或已终态时忽略，避免把 CANCEL_REQUESTED 覆盖回 RUNNING。 */
    public void modelCall(int step) {
        synchronized (lock) {
            if (terminal.get() || isCancelledStatus()) return;
            status = AgentExecutionStatus.RUNNING;
            phase = AgentExecutionPhase.MODEL_CALL;
            currentStep = step;
            touch();
        }
    }

    /** 标记进入模型重试退避；该阶段取消时应直接跳过后续重试。 */
    public void retryWait() {
        synchronized (lock) {
            if (terminal.get() || isCancelledStatus()) return;
            phase = AgentExecutionPhase.MODEL_RETRY_WAIT;
            touch();
        }
    }

    /** 标记工具批次结束、准备进入下一轮 think 的过渡阶段。 */
    public void betweenSteps(int step) {
        synchronized (lock) {
            if (terminal.get() || isCancelledStatus()) return;
            status = AgentExecutionStatus.RUNNING;
            phase = AgentExecutionPhase.BETWEEN_STEPS;
            currentStep = step;
            touch();
        }
    }

    /** 标记进入取消后的消息链补偿阶段。 */
    public void compensating() {
        synchronized (lock) {
            if (terminal.get()) return;
            phase = AgentExecutionPhase.COMPENSATING;
            touch();
        }
    }

    /** 标记进入终态收口阶段；随后 {@link #finish} 会写入 FINISHED。 */
    public void finalizing() {
        synchronized (lock) {
            if (terminal.get()) return;
            phase = AgentExecutionPhase.FINALIZING;
            touch();
        }
    }

    /**
     * 登记当前模型调用的取消句柄。
     *
     * <p>存在“取消先到、句柄后到”的竞态：如果取消已受理，这里会立即取消刚登记的句柄，避免调用继续阻塞。
     */
    public void registerModelHandle(CancellationHandle handle) {
        CancellationHandle registered = handle == null ? CancellationHandle.NONE : handle;
        boolean cancelNow;
        synchronized (lock) {
            modelHandle = registered;
            cancelNow = status == AgentExecutionStatus.CANCEL_REQUESTED;
        }
        // 锁外关闭，避免持锁期间做阻塞 I/O。
        if (cancelNow) registered.cancel();
    }

    /** 登记执行线程，作为 interrupt 兜底；真正的主取消手段仍是句柄和 Future。 */
    public void registerExecutionThread(Thread thread) {
        synchronized (lock) {
            executionThread = thread;
        }
    }

    /**
     * 注册取消受理后的回调。
     *
     * <p>如果注册时取消已经发生，会立即在调用线程执行一次，保证不会漏掉取消通知。
     */
    public void onCancel(Runnable listener) {
        boolean notifyNow;
        synchronized (lock) {
            cancelListener = listener;
            notifyNow = status == AgentExecutionStatus.CANCEL_REQUESTED;
        }
        if (notifyNow && listener != null) listener.run();
    }

    /**
     * 登记可取消的异步任务（工具执行、重试调度等）。
     *
     * <p>同样处理“取消先到”的竞态：取消已受理时立即 cancel(true)。
     */
    public void registerFuture(Future<?> future) {
        if (future == null) return;
        boolean cancelNow;
        synchronized (lock) {
            futures.add(future);
            cancelNow = status == AgentExecutionStatus.CANCEL_REQUESTED;
        }
        if (cancelNow) future.cancel(true);
    }

    /**
     * 开始一轮工具批次：重置工具快照，并登记本批全部工具。
     *
     * <p>已取消或已终态时直接忽略，避免把取消中的状态覆盖回 RUNNING。
     */
    public void beginTools(List<ToolExecutionRequest> requests, int step) {
        synchronized (lock) {
            if (terminal.get() || isCancelledStatus()) return;
            status = AgentExecutionStatus.RUNNING;
            phase = AgentExecutionPhase.TOOL_CALL;
            currentStep = step;
            tools.clear();
            if (requests != null) {
                for (ToolExecutionRequest request : requests) {
                    tools.put(request.id(), new MutableToolState(request.id(), request.name()));
                }
            }
            touch();
        }
    }

    public void toolRunning(String requestId) {
        updateTool(requestId, ToolExecutionStatus.RUNNING);
    }

    public void toolCompleted(String requestId) {
        updateTool(requestId, ToolExecutionStatus.COMPLETED);
    }

    public void toolFailed(String requestId) {
        updateTool(requestId, ToolExecutionStatus.FAILED);
    }

    /** 更新单个工具状态；requestId 不在本批快照中时只刷新更新时间。 */
    private void updateTool(String requestId, ToolExecutionStatus toolStatus) {
        synchronized (lock) {
            MutableToolState tool = tools.get(requestId);
            if (tool != null) tool.update(toolStatus);
            touch();
        }
    }

    /**
     * 受理一次主动取消请求。
     *
     * <p>执行分两段：先在锁内原子地把运行切到 CANCEL_REQUESTED 并快照所有取消句柄；再在锁外真正关闭连接、 取消
     * Future、中断线程并触发回调。锁外执行是为了避免持锁期间做阻塞 I/O 或调用外部代码，防止与模型/工具线程 互相等待。
     *
     * @param reason 取消原因；为空时使用默认文案
     * @return true 表示本次请求被受理；false 表示已取消或已终态
     */
    public boolean requestCancel(String reason) {
        CancellationHandle handle;
        Thread thread;
        List<Future<?>> activeFutures;
        Runnable listener;

        log.fine(
                "requestCancel 收到取消请求 executionId="
                        + executionId
                        + ", memoryId="
                        + memoryId
                        + ", reason="
                        + reason);

        // 第 1 段：锁内完成状态迁移与句柄快照。
        synchronized (lock) {
            // 幂等：已取消或已终态时直接拒绝，不重复关闭、补偿和通知。
            if (terminal.get() || status == AgentExecutionStatus.CANCEL_REQUESTED) {
                log.fine(
                        "requestCancel 忽略重复或已终态请求 executionId="
                                + executionId
                                + ", status="
                                + status
                                + ", terminal="
                                + terminal.get());
                return false;
            }

            // 先落 CANCEL_REQUESTED，让状态查询和迟到回调立即看到取消意图。
            status = AgentExecutionStatus.CANCEL_REQUESTED;
            cancelReason = reason == null ? "任务已被用户取消" : reason;

            // 尚未完成的工具统一标记 CANCEL_REQUESTED；已完成的保留真实结果。
            for (MutableToolState tool : tools.values()) {
                if (!tool.isFinished()) tool.update(ToolExecutionStatus.CANCEL_REQUESTED);
            }

            // 复制句柄快照，后续在锁外取消，避免持锁期间发生阻塞或死锁。
            handle = modelHandle;
            thread = executionThread;
            activeFutures = new ArrayList<Future<?>>(futures);
            listener = cancelListener;
            touch();
        }

        // 第 2 段：锁外主动中断底层任务。
        // 2.1 关闭当前模型调用：同步断开 HTTP，流式关闭 SSE 输入流与连接。
        boolean modelCancelled = handle.cancel();
        log.fine(
                "requestCancel 已关闭模型句柄 executionId="
                        + executionId
                        + ", cancelled="
                        + modelCancelled);

        // 2.2 取消工具执行、重试调度等异步任务；cancel(true) 会向执行线程发送中断。
        for (Future<?> future : activeFutures) future.cancel(true);
        log.fine(
                "requestCancel 已取消异步任务 executionId="
                        + executionId
                        + ", futures="
                        + activeFutures.size());

        // 2.3 interrupt 兜底：覆盖那些没有 Future、但仍阻塞在可中断调用上的执行线程。
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            log.fine(
                    "requestCancel 已中断执行线程 executionId="
                            + executionId
                            + ", thread="
                            + thread.getName());
        }

        // 2.4 最后一个空转窗口：立即通知流式执行收口，而不是等待模型回调。
        if (listener != null) {
            try {
                listener.run();
                log.fine("requestCancel 已触发取消监听 executionId=" + executionId);
            } catch (RuntimeException ignored) {
                // 取消已受理；调用方回调异常不能反向破坏取消动作。
            }
        }
        return true;
    }

    /**
     * 写入唯一一次终态。
     *
     * <p>正常完成、取消和异常都通过 {@code terminal} CAS 竞争，只有胜出者能写入 finalStatus/resultState 并触发后续 onStop 收口；返回
     * false 表示已经有其他路径先一步结束。
     */
    public boolean finish(
            AgentExecutionStatus finalStatus, AgentRunState finalResult, String error) {
        if (!terminal.compareAndSet(false, true)) {
            log.fine("finish 忽略已收口执行 executionId=" + executionId);
            return false;
        }
        synchronized (lock) {
            status = finalStatus;
            phase = AgentExecutionPhase.FINISHED;
            resultState = finalResult;
            errorMessage = error;
            updatedAtMillis = System.currentTimeMillis();
            // 收口时仍未完成的工具统一按取消处理，避免状态查询停留在 RUNNING。
            for (MutableToolState tool : tools.values()) {
                if (!tool.isFinished()) tool.update(ToolExecutionStatus.CANCELLED);
            }
            // 释放句柄引用，避免长期持有连接或线程对象。
            futures.clear();
            modelHandle = CancellationHandle.NONE;
            executionThread = null;
            log.fine(
                    "finish 写入终态 executionId="
                            + executionId
                            + ", status="
                            + finalStatus
                            + ", resultState="
                            + finalResult
                            + ", step="
                            + currentStep);
            return true;
        }
    }

    /**
     * 生成不可变状态快照。
     *
     * <p>在锁内一次性复制所有字段和工具列表，保证调用方看到的是同一时刻的一致状态；message 优先使用失败摘要， 其次使用取消原因。
     */
    public AgentExecutionState snapshot() {
        synchronized (lock) {
            List<RunningToolState> snapshots = new ArrayList<RunningToolState>();
            for (MutableToolState tool : tools.values()) snapshots.add(tool.snapshot());
            return new AgentExecutionState(
                    executionId,
                    status,
                    phase,
                    currentStep,
                    snapshots,
                    resultState,
                    errorMessage != null ? errorMessage : cancelReason,
                    updatedAtMillis);
        }
    }

    /** 调用方需持有 lock。 */
    private boolean isCancelledStatus() {
        return status == AgentExecutionStatus.CANCEL_REQUESTED
                || status == AgentExecutionStatus.CANCELLED;
    }

    /** 刷新最后更新时间；调用方需持有 lock。 */
    private void touch() {
        updatedAtMillis = System.currentTimeMillis();
    }

    /** 内部可变工具状态；只在 lock 内更新，对外通过 snapshot 暴露只读对象。 */
    private static final class MutableToolState {
        private final String requestId;
        private final String toolName;
        private ToolExecutionStatus status = ToolExecutionStatus.PENDING;
        private final long startedAtMillis = System.currentTimeMillis();
        private Long finishedAtMillis;

        private MutableToolState(String requestId, String toolName) {
            this.requestId = requestId;
            this.toolName = toolName;
        }

        private void update(ToolExecutionStatus value) {
            status = value;
            if (value == ToolExecutionStatus.COMPLETED
                    || value == ToolExecutionStatus.CANCELLED
                    || value == ToolExecutionStatus.FAILED)
                finishedAtMillis = System.currentTimeMillis();
        }

        private boolean isFinished() {
            return status == ToolExecutionStatus.COMPLETED
                    || status == ToolExecutionStatus.CANCELLED
                    || status == ToolExecutionStatus.FAILED;
        }

        private RunningToolState snapshot() {
            return new RunningToolState(
                    requestId, toolName, status, startedAtMillis, finishedAtMillis);
        }
    }
}
