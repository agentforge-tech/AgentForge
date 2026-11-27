package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板请求变换：把调用方的 prefix/suffix 请求转成"单个 prompt + 合并 stop"的下游请求。
 *
 * <p>同步（{@link TemplateFimModel}）与流式（{@link TemplateStreamingFimModel}）两条路径共用本类， 避免两份实现漂移 —— 与
 * {@code OpenAiFimWire} 的动机一致。
 *
 * @author changlu
 * @date 2026/11/26
 */
final class TemplateFimRequests {

    private TemplateFimRequests() {}

    /**
     * 应用模板。
     *
     * <p>三件事：prefix/suffix 按模板拼成单 prompt、suffix 置空、模板 stop 与调用方 stop 合并去重。
     */
    static FimRequest transform(FimRequest request, FimTemplate template) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        return FimRequest.builder()
                .prefix(template.buildPrompt(request.prefix(), request.suffix()))
                .suffix("")
                .filePath(request.filePath())
                .language(request.language())
                .context(request.context())
                .documentVersion(request.documentVersion())
                .parameters(mergeStops(request, template))
                .build();
    }

    /**
     * 合并模板 stop 与调用方 stop。
     *
     * <p><b>关键：必须先取调用方已有的 stop，再把模板 stop 追加进去。</b>
     *
     * <p>这里曾经踩过一个坑：早期实现是"用模板 stop 构造 overrides 去做 merge"， 而 {@link
     * DefaultFimRequestParameters#merge} 的语义是 overrides <b>整体替换</b> 同名列表。 结果调用方配置的结构级 stop（例如
     * {@code \n }\n}）被模板的 {@code <fim_pad>} 顶掉， 模型于是连续吐出多个方法，表面看像"stop 没生效"，实际是被模板层吃掉了。
     *
     * <p>所以顺序必须是：调用方 stop 在前（保持优先级），模板 stop 补充在后。
     */
    private static DefaultFimRequestParameters mergeStops(
            FimRequest request, FimTemplate template) {
        List<String> stops = new ArrayList<String>();

        // 1) 先放调用方/模型级 stop：这些是"业务语义"的收尾条件，优先级最高
        if (request.parameters() != null && request.parameters().stopSequences() != null) {
            for (String stop : request.parameters().stopSequences()) {
                if (stop != null && !stop.isEmpty() && !stops.contains(stop)) {
                    stops.add(stop);
                }
            }
        }

        // 2) 再补模板必需的特殊 Token stop：缺了它们模型会继续"写下一个文件"
        for (String stop : template.stopTokens()) {
            if (stop != null && !stop.isEmpty() && !stops.contains(stop)) {
                stops.add(stop);
            }
        }

        return DefaultFimRequestParameters.merge(
                request.parameters(),
                DefaultFimRequestParameters.builder().stopSequences(stops).build());
    }
}
