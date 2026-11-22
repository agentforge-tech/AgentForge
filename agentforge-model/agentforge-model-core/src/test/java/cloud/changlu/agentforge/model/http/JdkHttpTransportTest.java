package cloud.changlu.agentforge.model.http;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 校验 transport 层内置的 User-Agent 兜底能力。
 *
 * <p>背景：JDK 的 HttpURLConnection 未设置 User-Agent 时会自动补 {@code Java/1.8.0_x}，部分网关 （Cloudflare Browser
 * Integrity Check）会直接返回 403 / error code 1010。
 */
public class JdkHttpTransportTest {

    private HttpServer server;
    private String url;
    private final AtomicReference<String> receivedUserAgent = new AtomicReference<String>();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                new HttpHandler() {
                    @Override
                    public void handle(HttpExchange exchange) throws IOException {
                        receivedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
                        byte[] body = "data: {\"ok\":true}\n\n".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, body.length);
                        OutputStream output = exchange.getResponseBody();
                        output.write(body);
                        output.close();
                    }
                });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void shouldNeverSendJdkDefaultUserAgent() throws Exception {
        HttpResponse response = new JdkHttpTransport().execute(request(null));

        assertEquals(200, response.statusCode());
        String userAgent = receivedUserAgent.get();
        assertEquals(HttpUserAgent.defaultUserAgent(), userAgent);
        assertTrue(userAgent.startsWith("AgentForge/"));
        assertFalse(userAgent.startsWith("Java/"));
    }

    @Test
    public void shouldPreferExplicitRequestUserAgent() throws Exception {
        String explicit = "custom-client/2.3";
        new JdkHttpTransport().execute(request(explicit));

        assertEquals(explicit, receivedUserAgent.get());
    }

    @Test
    public void shouldUseConfiguredUserAgent() throws Exception {
        String configured = "AgentForge-Desktop/0.1.0";
        new JdkHttpTransport(configured).execute(request(null));

        assertEquals(configured, receivedUserAgent.get());
    }

    @Test
    public void shouldFallbackWhenConfiguredUserAgentBlank() throws Exception {
        new JdkHttpTransport("   ").execute(request(null));

        assertEquals(HttpUserAgent.defaultUserAgent(), receivedUserAgent.get());
    }

    @Test
    public void shouldSendUserAgentForStreamingRequests() throws Exception {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        CancellationHandle handle =
                new JdkHttpTransport("AgentForge-Stream/1.0")
                        .executeStreamingCancellable(
                                request(null),
                                new StreamingHttpResponseHandler() {
                                    @Override
                                    public void onOpen(
                                            int statusCode, Map<String, List<String>> headers) {}

                                    @Override
                                    public void onLine(String line) {}

                                    @Override
                                    public void onComplete() {
                                        completed.countDown();
                                    }

                                    @Override
                                    public void onError(Throwable throwable) {
                                        error.set(throwable);
                                        completed.countDown();
                                    }
                                });

        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertEquals(null, error.get());
        assertEquals("AgentForge-Stream/1.0", receivedUserAgent.get());
        assertTrue(handle instanceof CancellationHandle);
    }

    @Test
    public void shouldDetectUserAgentHeaderCaseInsensitively() {
        HttpRequest request = request(null);
        assertFalse(HttpUserAgent.containsUserAgent(request.headers()));

        HttpRequest withHeader =
                HttpRequest.builder().url("http://127.0.0.1/").header("user-agent", "x").build();
        assertTrue(HttpUserAgent.containsUserAgent(withHeader.headers()));
    }

    private HttpRequest request(String userAgent) {
        HttpRequest.Builder builder = HttpRequest.builder().url(url).body("{}");
        if (userAgent != null) {
            builder.header("User-Agent", userAgent);
        }
        return builder.build();
    }
}
