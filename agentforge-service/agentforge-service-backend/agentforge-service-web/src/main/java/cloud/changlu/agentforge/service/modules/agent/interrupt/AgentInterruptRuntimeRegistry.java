package cloud.changlu.agentforge.service.modules.agent.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service 首期 HITL 运行期注册表。
 *
 * <p>与 Framework 内存快照同生命周期：进程重启后不尝试伪恢复，批次由上层按过期处理。
 */
public class AgentInterruptRuntimeRegistry {

    public enum Status {
        PENDING,
        RESUMING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final Map<Object, RuntimeContext> runtimes = new ConcurrentHashMap<>();
    private final Map<String, BatchContext> batches = new ConcurrentHashMap<>();

    public void register(
            Object memoryId,
            long messageId,
            String aid,
            boolean debug,
            Long userId,
            String executionId,
            IAgentManager<Object, Object> manager,
            Object agent) {
        runtimes.put(
                memoryId,
                new RuntimeContext(
                        memoryId, messageId, aid, debug, userId, executionId, manager, agent));
    }

    public BatchContext suspend(Object memoryId, AgentSuspendInfo suspendInfo) {
        RuntimeContext runtime = requireRuntime(memoryId);
        BatchContext batch = new BatchContext(runtime, suspendInfo);
        BatchContext previous = batches.putIfAbsent(suspendInfo.getBatchId(), batch);
        return previous == null ? batch : previous;
    }

    public BatchContext claim(String batchId, Long userId) {
        BatchContext batch = requireOwned(batchId, userId);
        if (!batch.status.compareAndSet(Status.PENDING, Status.RESUMING)) {
            throw new IllegalStateException("BATCH_ALREADY_CONSUMED: " + batch.status.get());
        }
        return batch;
    }

    public BatchContext requireOwned(String batchId, Long userId) {
        BatchContext batch = requireBatch(batchId);
        if (batch.runtime.userId != null && !batch.runtime.userId.equals(userId)) {
            throw new SecurityException("INTERRUPT_OWNER_MISMATCH");
        }
        return batch;
    }

    public BatchContext get(String batchId) {
        return batches.get(batchId);
    }

    public BatchContext requireBatch(String batchId) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        BatchContext batch = batches.get(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("INTERRUPT_BATCH_NOT_FOUND");
        }
        return batch;
    }

    public void completed(String batchId) {
        update(batchId, Status.COMPLETED);
    }

    public void failed(String batchId) {
        update(batchId, Status.FAILED);
    }

    public BatchContext cancelByMemoryId(Object memoryId) {
        for (BatchContext batch : batches.values()) {
            if (batch.runtime.memoryId.equals(memoryId)
                    && batch.status.compareAndSet(Status.PENDING, Status.CANCELLED)) {
                return batch;
            }
        }
        return null;
    }

    public void release(Object memoryId) {
        runtimes.remove(memoryId);
    }

    private void update(String batchId, Status status) {
        BatchContext batch = batches.get(batchId);
        if (batch != null) {
            batch.status.set(status);
        }
    }

    private RuntimeContext requireRuntime(Object memoryId) {
        RuntimeContext runtime = runtimes.get(memoryId);
        if (runtime == null) {
            throw new IllegalStateException("HITL_RUNTIME_NOT_FOUND");
        }
        return runtime;
    }

    public static final class RuntimeContext {
        private final Object memoryId;
        private final long messageId;
        private final String aid;
        private final boolean debug;
        private final Long userId;
        private final String executionId;
        private final IAgentManager<Object, Object> manager;
        private final Object agent;

        private RuntimeContext(
                Object memoryId,
                long messageId,
                String aid,
                boolean debug,
                Long userId,
                String executionId,
                IAgentManager<Object, Object> manager,
                Object agent) {
            this.memoryId = memoryId;
            this.messageId = messageId;
            this.aid = aid;
            this.debug = debug;
            this.userId = userId;
            this.executionId = executionId;
            this.manager = manager;
            this.agent = agent;
        }

        public Object getMemoryId() {
            return memoryId;
        }

        public long getMessageId() {
            return messageId;
        }

        public String getAid() {
            return aid;
        }

        public boolean isDebug() {
            return debug;
        }

        public Long getUserId() {
            return userId;
        }

        public String getExecutionId() {
            return executionId;
        }

        public IAgentManager<Object, Object> getManager() {
            return manager;
        }

        public Object getAgent() {
            return agent;
        }
    }

    public static final class BatchContext {
        private final RuntimeContext runtime;
        private final AgentSuspendInfo suspendInfo;
        private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

        private BatchContext(RuntimeContext runtime, AgentSuspendInfo suspendInfo) {
            this.runtime = runtime;
            this.suspendInfo = suspendInfo;
        }

        public RuntimeContext getRuntime() {
            return runtime;
        }

        public AgentSuspendInfo getSuspendInfo() {
            return suspendInfo;
        }

        public Status getStatus() {
            return status.get();
        }

        public InterruptRequest interrupt(String interruptId) {
            for (InterruptRequest interrupt : suspendInfo.getInterrupts()) {
                if (interrupt.getInterruptId().equals(interruptId)) return interrupt;
            }
            return null;
        }

        public Map<String, Object> eventContent() {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("batchId", suspendInfo.getBatchId());
            content.put("messageId", runtime.messageId);
            content.put("status", status.get().name());
            content.put("interrupts", interruptEventContents());
            return content;
        }

        private List<Map<String, Object>> interruptEventContents() {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (InterruptRequest interrupt : suspendInfo.getInterrupts()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("interruptId", interrupt.getInterruptId());
                item.put("type", interrupt.getType().name());
                item.put("toolRequest", toolRequestContent(interrupt.getToolRequest()));
                item.put("payload", interrupt.getPayload());
                contents.add(item);
            }
            return contents;
        }

        private Map<String, Object> toolRequestContent(ToolExecutionRequest request) {
            if (request == null) return null;
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("id", request.id());
            content.put("name", request.name());
            content.put("arguments", request.arguments());
            return content;
        }
    }
}
