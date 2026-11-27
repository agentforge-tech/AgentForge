package cloud.changlu.demo.fim.service;

import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.demo.fim.config.FimProperties;
import cloud.changlu.demo.fim.dto.CompleteRequest;

import org.springframework.stereotype.Service;

/**
 * 把 HTTP 请求体转换为框架的 {@link FimRequest}。
 *
 * <p>抽成独立 service 的两个理由：
 *
 * <ol>
 *   <li>prefix 截断策略（本地 7B 上下文有限）只该有一处实现，流式与非流式共用；
 *   <li>controller 只负责协议适配（HTTP ↔ 对象），不掺业务规则。
 * </ol>
 *
 * @author changlu
 * @date 2026/11/26
 */
@Service
public class FimRequestAssembler {

    /**
     * prefix 上限。
     *
     * <p>行内补全真正需要的只是"紧邻光标"的上下文，把整个文件塞进去既慢又没用。 3000 字符约对应 800~1000 token，对本地 7B 是安全的量级。
     */
    private static final int MAX_PREFIX_CHARS = 3000;

    /** 截断后加的前缀标记，提示模型上文被裁剪过。 */
    private static final String TRUNCATE_MARKER = "...";

    private final FimProperties properties;

    public FimRequestAssembler(FimProperties properties) {
        this.properties = properties;
    }

    /** 由请求体构造 FIM 请求。 */
    public FimRequest assemble(CompleteRequest request) {
        String prefix = request.getPrefix() == null ? "" : request.getPrefix();
        String suffix = request.getSuffix() == null ? "" : request.getSuffix();

        FimRequest.Builder builder =
                FimRequest.builder().prefix(truncatePrefix(prefix)).suffix(suffix);

        String language = request.getLanguage();
        if (language != null && !language.trim().isEmpty()) {
            builder.language(language.trim());
        }
        return builder.build();
    }

    /**
     * 保留靠光标一侧的尾部内容。
     *
     * <p>必须保留<b>尾部</b>：FIM 依赖紧邻光标的上下文， 若保留头部会丢掉最关键的局部信息，补全质量直接崩。
     */
    private String truncatePrefix(String prefix) {
        if (prefix.length() <= MAX_PREFIX_CHARS) {
            return prefix;
        }
        return TRUNCATE_MARKER + prefix.substring(prefix.length() - MAX_PREFIX_CHARS);
    }

    /** 供前端展示当前生效的超时设置。 */
    public int timeoutSeconds() {
        return properties.getTimeoutSeconds();
    }
}
