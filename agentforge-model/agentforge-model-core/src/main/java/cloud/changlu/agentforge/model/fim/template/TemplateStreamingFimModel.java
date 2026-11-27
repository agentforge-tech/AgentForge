package cloud.changlu.agentforge.model.fim.template;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;

import java.util.Objects;

/**
 * {@link StreamingFimModel} 的模板装饰器，与非流式的 {@link TemplateFimModel} 对称。
 *
 * <p>流式路径同样需要套模板：否则会把 {@code <|fim_prefix|>} 之类的 Token 交给一个 不认识它们的服务端，表现为流式能连上但第一个片段就是空、或者补全完全跑偏。
 *
 * <p>变换规则与同步路径共用 {@link TemplateFimRequests}，保证两条路径不漂移。
 *
 * @author changlu
 * @date 2026/11/26
 */
public final class TemplateStreamingFimModel implements StreamingFimModel {

    private final StreamingFimModel delegate;
    private final FimTemplate template;

    public TemplateStreamingFimModel(StreamingFimModel delegate, FimTemplate template) {
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
    public void complete(FimRequest request, StreamingFimResponseHandler handler) {
        Objects.requireNonNull(handler, "handler");
        delegate.complete(TemplateFimRequests.transform(request, template), handler);
    }

    /** 取消必须透传，否则 IDE 场景下用户继续输入时会堆积无效请求。 */
    @Override
    public CancellationHandle completeCancellable(
            FimRequest request, StreamingFimResponseHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return delegate.completeCancellable(
                TemplateFimRequests.transform(request, template), handler);
    }
}
