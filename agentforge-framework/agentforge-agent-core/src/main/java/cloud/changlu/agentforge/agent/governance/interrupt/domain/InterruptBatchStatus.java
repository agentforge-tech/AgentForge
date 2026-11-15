package cloud.changlu.agentforge.agent.governance.interrupt.domain;

/** 中断批次的持久化状态；version 随状态推进递增，用于恢复侧 CAS。 */
public enum InterruptBatchStatus {
    PENDING,
    RESUMING,
    COMPLETED
}
