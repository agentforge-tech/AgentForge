package cloud.changlu.agentforge.model.fim;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;

import java.util.Objects;

/**
 * 可流式、可取消的 FIM 模型。
 *
 * <p>与 {@link cloud.changlu.agentforge.model.chat.StreamingChatModel} 对齐：回调式 API 保持 JDK 8
 * 兼容，Provider 负责把自家流式协议翻译成增量片段、最终响应与错误。
 *
 * @author changlu
 * @date 2026/11/25
 */
public interface StreamingFimModel {

    /**
     * 启动流式补全。
     *
     * @param request provider-neutral 请求
     * @param handler 增量片段、最终响应或错误
     */
    void complete(FimRequest request, StreamingFimResponseHandler handler);

    /**
     * 启动可取消的流式补全。
     *
     * <p>IDE 场景必须使用这个入口：用户继续输入时要能真正掐断上行请求， 否则连打一行字就会堆积数十个无效请求。
     */
    default CancellationHandle completeCancellable(
            FimRequest request, StreamingFimResponseHandler handler) {
        Objects.requireNonNull(handler, "handler");
        complete(request, handler);
        return CancellationHandle.NONE;
    }
}
