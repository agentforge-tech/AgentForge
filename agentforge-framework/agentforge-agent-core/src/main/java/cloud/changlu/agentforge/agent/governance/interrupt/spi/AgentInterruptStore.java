package cloud.changlu.agentforge.agent.governance.interrupt.spi;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;

/**
 * 中断批次存储 SPI。
 *
 * <p>并发恢复由状态机 CAS 保护：PENDING 只能被一个请求推进为 RESUMING；完成后进入 COMPLETED。 持久化实现应在同一事务中完成状态判断与更新。
 */
public interface AgentInterruptStore {
    void save(AgentInterruptSnapshot snapshot);

    AgentInterruptSnapshot get(String batchId);

    /**
     * 原子占有待恢复批次。
     *
     * @param batchId 批次ID
     */
    void claim(String batchId);

    void complete(String batchId);
}
