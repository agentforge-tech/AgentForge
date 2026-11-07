package cloud.changlu.agentforge.model.http;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;

import java.io.IOException;

/** Small transport SPI so providers are independent from a particular HTTP library. */
public interface HttpTransport {

    HttpResponse execute(HttpRequest request) throws IOException;

    default HttpCall newCall(final HttpRequest request) {
        final HttpTransport transport = this;
        return new HttpCall() {
            @Override
            public HttpResponse execute() throws IOException {
                return transport.execute(request);
            }

            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    /**
     * Starts a streaming HTTP request.
     *
     * <p>The default implementation keeps existing custom transports source-compatible and reports
     * that streaming is unsupported. Transports that support streaming should override this method.
     *
     * @author changlu
     * @date 2026/09/13
     */
    default void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        handler.onError(
                new UnsupportedOperationException(
                        "Streaming HTTP is not supported by " + getClass().getName()));
    }

    default CancellationHandle executeStreamingCancellable(
            HttpRequest request, StreamingHttpResponseHandler handler) {
        executeStreaming(request, handler);
        return CancellationHandle.NONE;
    }
}
