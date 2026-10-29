package cloud.changlu.agentforge.service.env;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
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
}
