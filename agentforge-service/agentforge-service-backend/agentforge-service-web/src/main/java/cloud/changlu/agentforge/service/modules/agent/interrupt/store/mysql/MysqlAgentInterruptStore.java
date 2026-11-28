package cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentContextAccessor;
import cloud.changlu.agentforge.service.modules.agent.runtime.context.ServiceAgentExecutionContext;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.Date;

public final class MysqlAgentInterruptStore implements AgentInterruptStore {
    private final AgentInterruptSnapshotMapper mapper;
    private final AgentInterruptSnapshotCodec codec;
    private final ServiceAgentContextAccessor contextAccessor;

    public MysqlAgentInterruptStore(
            AgentInterruptSnapshotMapper mapper,
            AgentInterruptSnapshotCodec codec,
            ServiceAgentContextAccessor contextAccessor) {
        this.mapper = mapper;
        this.codec = codec;
        this.contextAccessor = contextAccessor;
    }

    @Override
    public void save(AgentInterruptSnapshot snapshot, AgentChatContext context) {
        ServiceAgentExecutionContext executionContext = contextAccessor.requireForHitl(context);
        persist(
                snapshot.withContextAttributes(
                        contextAccessor.toSnapshotAttributes(executionContext)));
    }

    private void persist(AgentInterruptSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("interrupt snapshot must not be null");
        }
        String batchId = snapshot.getBatchId();
        Date now = new Date();
        AgentInterruptSnapshotPojo pojo = new AgentInterruptSnapshotPojo();
        pojo.setBatchId(batchId);
        pojo.setMemoryId(codec.memoryId(snapshot.getMemoryId()));
        pojo.setSourceExecutionId(snapshot.getSourceExecutionId());
        pojo.setStep(snapshot.getStep());
        pojo.setStatus(snapshot.getStatus().name());
        pojo.setSchemaVersion(codec.schemaVersion());
        pojo.setSnapshotJson(codec.encode(snapshot));
        pojo.setVersion(0L);
        pojo.setCreateTime(now);
        pojo.setModifyTime(now);
        try {
            mapper.insert(pojo);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("interrupt batch already exists: " + batchId, e);
        }
    }

    @Override
    public AgentInterruptSnapshot get(String batchId) {
        AgentInterruptSnapshotPojo pojo = mapper.selectByBatchId(batchId);
        if (pojo == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        return codec.decode(pojo);
    }

    @Override
    public void claim(String batchId) {
        if (mapper.claim(batchId, new Date()) == 1) {
            return;
        }
        AgentInterruptSnapshotPojo current = mapper.selectByBatchId(batchId);
        if (current == null) {
            throw new IllegalArgumentException("interrupt batch not found: " + batchId);
        }
        InterruptBatchStatus status = codec.status(current.getStatus(), batchId);
        if (status == InterruptBatchStatus.COMPLETED) {
            throw new IllegalStateException("interrupt batch already completed: " + batchId);
        }
        if (status == InterruptBatchStatus.RESUMING) {
            throw new IllegalStateException("interrupt batch is being resumed: " + batchId);
        }
        throw new IllegalStateException(
                "interrupt batch claim conflict, status=" + status + ", batch=" + batchId);
    }

    @Override
    public void complete(String batchId) {
        mapper.complete(batchId, new Date());
    }

    @Override
    public AgentInterruptSnapshot cancelPendingByMemoryId(Object memoryId) {
        AgentInterruptSnapshotPojo current =
                mapper.selectPendingByMemoryId(codec.memoryId(memoryId));
        if (current == null || mapper.cancel(current.getBatchId(), new Date()) != 1) {
            return null;
        }
        current.setStatus(InterruptBatchStatus.CANCELLED.name());
        return codec.decode(current);
    }
}
