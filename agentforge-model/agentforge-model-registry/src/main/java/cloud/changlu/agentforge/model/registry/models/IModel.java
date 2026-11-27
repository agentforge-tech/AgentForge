package cloud.changlu.agentforge.model.registry.models;

import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;

/**
 * @description llm模型构建接口，FIM 作为并列能力扩展，后续 EmbeddingModel 等能力在这里继续扩展
 * @author changlu
 * @date 2026/9/16
 */
public interface IModel {

    /**
     * 构造同步 ChatModel
     *
     * @param llmBasicConfig LlmBasicConfig
     * @return ChatModel
     */
    ChatModel buildChatModel(LlmBasicConfig llmBasicConfig);

    /**
     * 构造流式 StreamingChatModel
     *
     * @param llmBasicConfig LlmBasicConfig
     * @return StreamingChatModel
     */
    StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig);

    /**
     * 构造同步 FimModel（中间补全）。
     *
     * <p>默认不支持，保证现有 Chat-only Provider 无需改动即可编译。
     *
     * @param llmBasicConfig LlmBasicConfig
     * @return FimModel
     */
    default FimModel buildFimModel(LlmBasicConfig llmBasicConfig) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support FIM completion");
    }

    /**
     * 构造流式 StreamingFimModel。
     *
     * @param llmBasicConfig LlmBasicConfig
     * @return StreamingFimModel
     */
    default StreamingFimModel buildStreamingFimModel(LlmBasicConfig llmBasicConfig) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support streaming FIM completion");
    }
}
