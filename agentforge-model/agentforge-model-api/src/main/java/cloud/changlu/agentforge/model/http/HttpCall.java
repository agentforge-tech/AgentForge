package cloud.changlu.agentforge.model.http;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;

import java.io.IOException;

/** A single executable and cancellable HTTP request. */
public interface HttpCall extends CancellationHandle {
    HttpResponse execute() throws IOException;
}
