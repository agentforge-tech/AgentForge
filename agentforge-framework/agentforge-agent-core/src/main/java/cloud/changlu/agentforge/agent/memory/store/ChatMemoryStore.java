package cloud.changlu.agentforge.agent.memory.store;

import cloud.changlu.agentforge.model.chat.message.ChatMessage;

import java.util.List;

/**
 * @description 会话记忆的存储抽象，只负责「按 memoryId 存取消息列表」，不关心窗口裁剪。
 *     <p>窗口裁剪由 {@code MessageWindowChatMemory} 负责，存储只做纯读写，因此内存、MySQL、Redis 等实现可以自由替换而不影响裁剪语义。
 * @author changlu
 * @date 2026/10/25
 */
public interface ChatMemoryStore {

    /** 读取该会话存储的全部消息；不存在时返回空列表 */
    List<ChatMessage> getMessages(Object memoryId);

    /** 整段覆盖写入该会话的消息列表 */
    void updateMessages(Object memoryId, List<ChatMessage> messages);

    /** 删除该会话的全部消息 */
    void deleteMessages(Object memoryId);
}
