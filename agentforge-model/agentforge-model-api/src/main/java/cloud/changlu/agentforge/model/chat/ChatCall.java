package cloud.changlu.agentforge.model.chat;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;

/** A single executable and cancellable non-streaming model call. */
public interface ChatCall extends CancellationHandle {
    ChatResponse execute();
}
