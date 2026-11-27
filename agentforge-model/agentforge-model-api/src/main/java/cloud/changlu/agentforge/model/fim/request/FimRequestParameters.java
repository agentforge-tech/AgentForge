package cloud.changlu.agentforge.model.fim.request;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral FIM 采样参数。
 *
 * <p>对应 {@link cloud.changlu.agentforge.model.chat.request.ChatRequestParameters} 的位置。 所有 getter
 * 返回包装类型，{@code null} 统一表示"不设置"，由 {@link DefaultFimRequestParameters#merge} 完成模型级默认值与请求级覆盖的合并。
 *
 * @author changlu
 * @date 2026/11/25
 */
public interface FimRequestParameters {

    /** 单次补全上限。DeepSeek 官方说明模型最大补全长度为 4K。 */
    Integer maxTokens();

    Double temperature();

    Double topP();

    /** 补全场景强烈建议设置：否则模型可能一次吐出一整个类。 */
    List<String> stopSequences();

    /**
     * 厂商特有参数（如 DeepSeek 的 {@code thinking}、Ollama 的 {@code raw}）。
     *
     * <p>与 Chat 侧 {@code customParameters} 同一策略：只作为临时通道，语义稳定后应升级为强类型。
     */
    Map<String, Object> customParameters();
}
