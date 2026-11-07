package cloud.changlu.agentforge.service.env;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * Typed access to AgentForge Service environment properties.
 *
 * <p>Application components should read shared runtime configuration through this context instead
 * of scattering property keys and defaults across the codebase.
 *
 * @author changlu
 * @since 2026-10-29
 */
@Component
public class EnvironmentContext {

    private static final String DEFAULT_UPLOAD_DIR = "./upload";
    private static final String DEFAULT_UPLOAD_VISIT_PATH = "/upload";
    private static final String DEFAULT_UPLOAD_VISIT_PROTOCOL = "http";
    private static final String DEFAULT_UPLOAD_VISIT_HOST = "127.0.0.1";
    private static final String DEFAULT_UPLOAD_MAX_SIZE = "10MB";
    private static final String DEFAULT_SERVER_PORT = "8080";
    private static final String DEFAULT_CHAT_EVENT_STORE_TYPE = "inmemory";
    private static final long DEFAULT_CHAT_CANCEL_WAIT_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_CHAT_CANCEL_POLL_INTERVAL_MS = 200L;

    private final Environment environment;

    public EnvironmentContext(Environment environment) {
        this.environment = environment;
    }

    /** OpenReach service endpoint. An empty value disables the related web tools. */
    public String getOpenReachBaseUrl() {
        return environment.getProperty("agentforge.openreach.base-url", "");
    }

    /** Physical root directory used by the local file storage implementation. */
    public String getFileLocalStoragePath() {
        return environment.getProperty("agentforge.file.local.storage-path", getUserDirUpload());
    }

    /** URL path under which locally uploaded files are exposed. */
    public String getFileLocalVisitPath() {
        return environment.getProperty(
                "agentforge.file.local.visit-path", DEFAULT_UPLOAD_VISIT_PATH);
    }

    /** Protocol used to build the complete public URL returned after a local upload. */
    public String getFileLocalVisitProtocol() {
        return environment.getProperty(
                "agentforge.file.local.visit-protocol", DEFAULT_UPLOAD_VISIT_PROTOCOL);
    }

    /** Host or IP used to build the complete public URL returned after a local upload. */
    public String getFileLocalVisitHost() {
        return environment.getProperty(
                "agentforge.file.local.visit-host", DEFAULT_UPLOAD_VISIT_HOST);
    }

    /** External port used to build the complete public URL returned after a local upload. */
    public String getFileLocalVisitPort() {
        return environment.getProperty("agentforge.file.local.visit-port", getServerPort());
    }

    /** Maximum size accepted by the standard upload service, in bytes. */
    public long getFileUploadMaxSize() {
        String configured =
                environment.getProperty("agentforge.file.max-size", DEFAULT_UPLOAD_MAX_SIZE);
        return DataSize.parse(configured).toBytes();
    }

    /** Upload directory resolved before Spring starts, with a standalone fallback. */
    public String getUserDirUpload() {
        return environment.getProperty("user.dir.upload", DEFAULT_UPLOAD_DIR);
    }

    public String getServerPort() {
        return environment.getProperty("server.port", DEFAULT_SERVER_PORT);
    }

    /**
     * Detached chat event storage mode: {@code mysql}, {@code inmemory}, or reserved {@code redis}.
     */
    public String getChatEventStoreType() {
        return environment
                .getProperty("agentforge.chat.event-store.type", DEFAULT_CHAT_EVENT_STORE_TYPE)
                .trim()
                .toLowerCase();
    }

    /** 停止接口等待 Agent 正常收口的最长时间，单位毫秒。 */
    public long getChatCancelWaitTimeoutMs() {
        long value =
                environment.getProperty(
                        "agentforge.chat.cancel.wait-timeout-ms",
                        Long.class,
                        DEFAULT_CHAT_CANCEL_WAIT_TIMEOUT_MS);
        return value > 0L ? value : DEFAULT_CHAT_CANCEL_WAIT_TIMEOUT_MS;
    }

    /** 停止接口轮询消息状态的间隔，单位毫秒；必须小于等待超时。 */
    public long getChatCancelPollIntervalMs() {
        long value =
                environment.getProperty(
                        "agentforge.chat.cancel.poll-interval-ms",
                        Long.class,
                        DEFAULT_CHAT_CANCEL_POLL_INTERVAL_MS);
        if (value <= 0L) {
            return DEFAULT_CHAT_CANCEL_POLL_INTERVAL_MS;
        }
        long timeout = getChatCancelWaitTimeoutMs();
        return value < timeout ? value : Math.max(1L, timeout / 2);
    }

    /**
     * External directory that contains the built frontend ({@code index.html}, {@code assets/}).
     */
    public String getWebStaticLocation() {
        String defaultLocation = "file:" + getUserDirConf() + "static/";
        return environment.getProperty("agentforge.web.static-location", defaultLocation);
    }

    private String getUserDirConf() {
        String dir = environment.getProperty("user.dir.conf");
        if (!StringUtils.hasText(dir)) {
            return "./conf/";
        }
        return dir.endsWith("/") || dir.endsWith("\\") ? dir : dir + "/";
    }
}
