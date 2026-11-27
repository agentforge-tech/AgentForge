package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.fim.FimCall;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimResponse;

/**
 * 把"特殊 Token 型 FIM"适配到"原生 suffix 型端点"上。
 *
 * <p>核心转换：把 prefix/suffix 按模板拼成单个 prompt，suffix 置空， 并把模板 stop 与调用方 stop 合并。
 *
 * <p>这样同一个 HTTP Provider（例如 {@code OpenAiFimModel}）既能直连 DeepSeek（原生 suffix）， 也能服务 Qwen（特殊
 * Token），切换模型只换模板，不动网络层。
 *
 * <p><b>注意</b>：{@link cloud.changlu.agentforge.model.fim.FimFormat#NATIVE_SUFFIX} 形态 不应经过本装饰器（会把
 * suffix 置空导致后文丢失），请直接把模板交给 Provider 使用。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class TemplateFimModel implements FimModel {

    private final FimModel delegate;
    private final FimTemplate template;

    public TemplateFimModel(FimModel delegate, FimTemplate template) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (template == null) {
            throw new IllegalArgumentException("template must not be null");
        }
        this.delegate = delegate;
        this.template = template;
    }

    public FimTemplate template() {
        return template;
    }

    @Override
    public FimResponse complete(FimRequest request) {
        return newCall(request).execute();
    }

    @Override
    public FimCall newCall(FimRequest request) {
        // suffix 已进入 prompt，因此底层请求不再发送 suffix 字段。
        return delegate.newCall(TemplateFimRequests.transform(request, template));
    }
}
