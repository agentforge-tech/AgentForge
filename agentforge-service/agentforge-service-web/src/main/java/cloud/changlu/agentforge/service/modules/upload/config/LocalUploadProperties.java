package cloud.changlu.agentforge.service.modules.upload.config;

import cloud.changlu.agentforge.service.env.EnvironmentContext;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local file upload configuration.
 *
 * @author changlu
 * @since 2026-10-29
 */
@Component
public class LocalUploadProperties {

    private final Path storageRoot;
    private final String visitBaseUrl;
    private final String visitPath;
    private final long maxFileSize;

    public LocalUploadProperties(EnvironmentContext environmentContext) {
        String storagePath = environmentContext.getFileLocalStoragePath();
        String visitPath = environmentContext.getFileLocalVisitPath();
        if (!StringUtils.hasText(storagePath)) {
            throw new IllegalArgumentException("Local upload storage path must not be empty");
        }
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        this.visitPath = normalizeVisitPath(visitPath);
        this.visitBaseUrl =
                buildVisitBaseUrl(
                        environmentContext.getFileLocalVisitProtocol(),
                        environmentContext.getFileLocalVisitHost(),
                        environmentContext.getFileLocalVisitPort());
        this.maxFileSize = environmentContext.getFileUploadMaxSize();
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("Upload max file size must be greater than zero");
        }
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public String getVisitPath() {
        return visitPath;
    }

    public String getVisitBaseUrl() {
        return visitBaseUrl;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public String getResourcePattern() {
        return visitPath + "/**";
    }

    public String buildVisitUrl(String relativePath) {
        String normalizedPath = relativePath.replace('\\', '/');
        return visitBaseUrl + visitPath + "/" + normalizedPath;
    }

    private static String buildVisitBaseUrl(String protocol, String host, String port) {
        if (!StringUtils.hasText(protocol)
                || !protocol.trim().matches("(?i)https?")
                || !StringUtils.hasText(host)
                || !StringUtils.hasText(port)
                || !port.trim().matches("\\d{1,5}")) {
            throw new IllegalArgumentException("Invalid local upload visit address configuration");
        }
        int portNumber = Integer.parseInt(port.trim());
        if (portNumber < 1 || portNumber > 65535) {
            throw new IllegalArgumentException("Invalid local upload visit port");
        }
        String normalizedHost = host.trim();
        if (normalizedHost.contains(":")
                && !normalizedHost.startsWith("[")
                && !normalizedHost.endsWith("]")) {
            normalizedHost = "[" + normalizedHost + "]";
        }
        return protocol.trim().toLowerCase() + "://" + normalizedHost + ":" + portNumber;
    }

    private static String normalizeVisitPath(String rawVisitPath) {
        String normalized = StringUtils.hasText(rawVisitPath) ? rawVisitPath.trim() : "/upload";
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("Local upload visit path must not be root path");
        }
        return normalized;
    }
}
