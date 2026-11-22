package cloud.changlu.agentforge.model.registry.constant;

/**
 * @description llm相关配置参数
 * @author changlu
 * @date 2026/9/16
 */
public interface LlmConstant {

    // 通用参数
    // 秒为单位
    String TIMEOUT = "timeout";
    String TEMPERATURE = "temperature";
    String TOP_P = "topP";
    String MAX_TOKENS = "maxTokens";
    String RETURN_THINKING = "returnThinking";

    // 客户端标识，未设置时由 framework transport 兜底。部分网关会拒绝 JDK 默认的 Java/1.x 签名
    String USER_AGENT = "userAgent";
}
