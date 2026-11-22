package cloud.changlu.agentforge.model.http;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dependency-free HTTP transport based on JDK {@link HttpURLConnection}.
 *
 * <p>Streaming requests are consumed on daemon worker threads so the caller is not blocked while an
 * SSE response is being read.
 *
 * @author changlu
 * @date 2026/09/13
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final AtomicInteger STREAM_THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService STREAM_EXECUTOR =
            Executors.newCachedThreadPool(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "agentforge-http-stream-"
                                                    + STREAM_THREAD_SEQUENCE.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        }
                    });

    private final String userAgent;

    /** 使用框架内置的 User-Agent。 */
    public JdkHttpTransport() {
        this(HttpUserAgent.defaultUserAgent());
    }

    /**
     * 使用自定义 User-Agent。传入空白值时回退到框架内置值。
     *
     * <p>显式设置可让网关侧识别真实调用方；同时避免 JDK 默认的 {@code Java/1.8.0_x} 被 Cloudflare Browser Integrity Check
     * 直接拒绝（HTTP 403 / error code: 1010）。
     */
    public JdkHttpTransport(String userAgent) {
        this.userAgent =
                userAgent == null || userAgent.trim().isEmpty()
                        ? HttpUserAgent.defaultUserAgent()
                        : userAgent.trim();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        return newCall(request).execute();
    }

    @Override
    public HttpCall newCall(final HttpRequest request) {
        return new JdkHttpCall(request, userAgent);
    }

    @Override
    public void executeStreaming(
            final HttpRequest request, final StreamingHttpResponseHandler handler) {
        executeStreamingCancellable(request, handler);
    }

    @Override
    public CancellationHandle executeStreamingCancellable(
            final HttpRequest request, final StreamingHttpResponseHandler handler) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        final JdkStreamingCancellationHandle cancellation = new JdkStreamingCancellationHandle();
        final String streamingUserAgent = userAgent;
        Future<?> future =
                STREAM_EXECUTOR.submit(
                        new Runnable() {
                            @Override
                            public void run() {
                                stream(request, handler, cancellation, streamingUserAgent);
                            }
                        });
        cancellation.setFuture(future);
        return cancellation;
    }

    private static void stream(
            HttpRequest request,
            StreamingHttpResponseHandler handler,
            JdkStreamingCancellationHandle cancellation,
            String userAgent) {
        HttpURLConnection connection = null;
        try {
            if (cancellation.isCancelled()) return;
            connection = openConnection(request, userAgent);
            cancellation.setConnection(connection);
            writeBody(connection, request.body());
            if (cancellation.isCancelled()) return;
            int status = connection.getResponseCode();
            Map<String, List<String>> headers = connection.getHeaderFields();
            handler.onOpen(status, headers);

            InputStream stream = responseStream(connection, status);
            if (stream != null) {
                cancellation.setInputStream(stream);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancellation.isCancelled()) return;
                        handler.onLine(line);
                    }
                } finally {
                    reader.close();
                }
            }
            if (!cancellation.isCancelled()) handler.onComplete();
        } catch (Throwable error) {
            if (cancellation.isCancelled()) return;
            try {
                handler.onError(error);
            } catch (Throwable ignored) {
                // User callback failures must not escape the transport worker thread.
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            cancellation.clearResources();
        }
    }

    private static final class JdkStreamingCancellationHandle implements CancellationHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<HttpURLConnection> connection =
                new AtomicReference<HttpURLConnection>();
        private final AtomicReference<InputStream> inputStream = new AtomicReference<InputStream>();
        private final AtomicReference<Future<?>> future = new AtomicReference<Future<?>>();

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            InputStream stream = inputStream.getAndSet(null);
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            HttpURLConnection activeConnection = connection.getAndSet(null);
            if (activeConnection != null) activeConnection.disconnect();
            Future<?> activeFuture = future.getAndSet(null);
            if (activeFuture != null) activeFuture.cancel(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        private void setConnection(HttpURLConnection value) {
            connection.set(value);
            if (cancelled.get()) value.disconnect();
        }

        private void setInputStream(InputStream value) {
            inputStream.set(value);
            if (cancelled.get()) {
                try {
                    value.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void setFuture(Future<?> value) {
            future.set(value);
            if (cancelled.get()) value.cancel(true);
        }

        private void clearResources() {
            inputStream.set(null);
            connection.set(null);
            future.set(null);
        }
    }

    private static final class JdkHttpCall implements HttpCall {
        private final HttpRequest request;
        private final String userAgent;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<HttpURLConnection> connection =
                new AtomicReference<HttpURLConnection>();
        private final AtomicReference<InputStream> inputStream = new AtomicReference<InputStream>();

        private JdkHttpCall(HttpRequest request, String userAgent) {
            this.request = request;
            this.userAgent = userAgent;
        }

        @Override
        public HttpResponse execute() throws IOException {
            if (cancelled.get()) throw new IOException("HTTP call cancelled");
            HttpURLConnection activeConnection = openConnection(request, userAgent);
            connection.set(activeConnection);
            try {
                writeBody(activeConnection, request.body());
                if (cancelled.get()) throw new IOException("HTTP call cancelled");
                int status = activeConnection.getResponseCode();
                InputStream stream = responseStream(activeConnection, status);
                inputStream.set(stream);
                return new HttpResponse(status, readBody(stream));
            } finally {
                inputStream.set(null);
                connection.set(null);
                activeConnection.disconnect();
            }
        }

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            InputStream stream = inputStream.getAndSet(null);
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            HttpURLConnection activeConnection = connection.getAndSet(null);
            if (activeConnection != null) activeConnection.disconnect();
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static HttpURLConnection openConnection(HttpRequest request, String userAgent)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
        connection.setRequestMethod(request.method());
        connection.setConnectTimeout(request.connectTimeoutMillis());
        connection.setReadTimeout(request.readTimeoutMillis());
        connection.setUseCaches(false);

        boolean hasUserAgent = false;
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            if (HttpUserAgent.HEADER.equalsIgnoreCase(header.getKey())) {
                hasUserAgent = true;
            }
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        // JDK 未设置 User-Agent 时会补上 Java/1.8.0_x，部分网关（Cloudflare BIC）会直接 403，
        // 因此这里始终兜底一个产品化的 User-Agent；调用方显式设置时以调用方为准。
        if (!hasUserAgent) {
            String fallback = userAgent == null ? HttpUserAgent.defaultUserAgent() : userAgent;
            connection.setRequestProperty(HttpUserAgent.HEADER, fallback);
        }
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, String body) throws IOException {
        if (body == null || body.isEmpty()) {
            return;
        }
        connection.setDoOutput(true);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(payload);
            output.flush();
        } finally {
            output.close();
        }
    }

    private static InputStream responseStream(HttpURLConnection connection, int status)
            throws IOException {
        return status >= 200 && status < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }
}
