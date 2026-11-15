package cloud.changlu.agentforge.agent.governance.interrupt.support;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单机默认实现；生产集群应替换为具备同等状态 CAS 的持久化 Store。 */
public final class InMemoryAgentInterruptStore implements AgentInterruptStore {
    private final Map<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    public void save(AgentInterruptSnapshot snapshot) {
        if (entries.putIfAbsent(snapshot.getBatchId(), new Entry(snapshot)) != null) {
            throw new IllegalStateException(
                    "interrupt batch already exists: " + snapshot.getBatchId());
        }
    }

    public AgentInterruptSnapshot get(String batchId) {
        return entry(batchId).snapshot;
    }

    public void claim(String batchId) {
        Entry entry = entry(batchId);
        synchronized (entry) {
            InterruptBatchStatus status = entry.snapshot.getStatus();
            if (status == InterruptBatchStatus.COMPLETED) {
                throw new IllegalStateException("interrupt batch already completed: " + batchId);
            }
            if (status == InterruptBatchStatus.RESUMING) {
                throw new IllegalStateException("interrupt batch is being resumed: " + batchId);
            }
            entry.snapshot = entry.snapshot.withStatus(InterruptBatchStatus.RESUMING);
        }
    }

    public void complete(String batchId) {
        Entry entry = entries.get(batchId);
        if (entry == null) return;
        synchronized (entry) {
            entry.snapshot = entry.snapshot.withStatus(InterruptBatchStatus.COMPLETED);
        }
    }

    private Entry entry(String batchId) {
        Entry entry = entries.get(batchId);
        if (entry == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        return entry;
    }

    private static final class Entry {
        private AgentInterruptSnapshot snapshot;

        private Entry(AgentInterruptSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }
}
