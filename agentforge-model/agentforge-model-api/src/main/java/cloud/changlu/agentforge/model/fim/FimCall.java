package cloud.changlu.agentforge.model.fim;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.fim.response.FimResponse;

/**
 * 一次可执行、可取消的非流式补全调用。
 *
 * <p>与 {@link cloud.changlu.agentforge.model.chat.ChatCall} 对齐：实现方应把取消委托给底层 {@code
 * HttpCall}，保证取消能真正中断上行请求。
 *
 * @author changlu
 * @date 2026/11/25
 */
public interface FimCall extends CancellationHandle {

    /**
     * 执行补全。
     *
     * @return 归一化响应
     */
    FimResponse execute();
}
