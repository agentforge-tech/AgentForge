package cloud.changlu.agentforge.agent.memory;

/**
 * @description 按memoryId提供会话记忆
 * @author changlu
 * @date 2026/9/16
 */
public interface ChatMemoryProvider {

    /** 获取（不存在则创建）某个会话的记忆 */
    ChatMemory get(Object memoryId);
}
