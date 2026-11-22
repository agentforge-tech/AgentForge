package cloud.changlu.agentforge.agent.extend.middlewares.read.tool;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResourceKind;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 无状态 HTTP 元数据探测器。只读取响应头，不下载正文。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class HttpReadResourceFetcher implements ReadResourceFetcher {

    private static final Pattern PRIVATE_10 = Pattern.compile("^10\\..*");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
    private static final Pattern LOOPBACK_127 = Pattern.compile("^127\\..*");

    private final ReadToolOptions options;
    private final Logger log;

    public HttpReadResourceFetcher(ReadToolOptions options, Logger log) {
        this.options = options == null ? ReadToolOptions.defaults() : options;
        this.log = log == null ? Logger.getLogger(HttpReadResourceFetcher.class.getName()) : log;
    }

    @Override
    public ReadBatchResult fetch(List<String> paths, String hint) {
        ReadBatchResult batch = new ReadBatchResult();
        if (paths == null || paths.isEmpty()) {
            return ReadBatchResult.failure("EMPTY_PATHS", "paths must not be empty");
        }
        if (paths.size() > options.getMaxPaths()) {
            return ReadBatchResult.failure(
                    "TOO_MANY_PATHS", "at most " + options.getMaxPaths() + " paths per call");
        }
        List<ReadResult> results = new ArrayList<ReadResult>();
        for (String path : paths) {
            results.add(probe(path));
        }
        batch.setResults(results);
        return batch;
    }

    private ReadResult probe(String path) {
        long start = System.currentTimeMillis();
        try {
            URL url = new URL(path);
            if (!"http".equalsIgnoreCase(url.getProtocol())
                    && !"https".equalsIgnoreCase(url.getProtocol())) {
                return ReadResult.failure(
                        path, "UNSUPPORTED_SCHEME", "only http/https is supported");
            }
            String host = url.getHost();
            if (!isPublicHost(host)) {
                return ReadResult.failure(
                        path, "SSRF_BLOCKED", "private or loopback host is blocked");
            }
            ResponseMeta meta = head(url);
            ReadResult result = new ReadResult();
            result.setOk(true);
            result.setKind(kind(meta.contentType, path));
            result.setUrl(url.toExternalForm());
            result.setName(name(url));
            result.setMimeType(meta.contentType);
            result.setFileType(extension(path));
            result.setSize(meta.contentLength);
            result.setSource("web");
            result.setDedupeKey(url.toExternalForm());
            log.fine(
                    "read_resource.probe url="
                            + safe(url.toExternalForm())
                            + " status="
                            + meta.status
                            + " contentType="
                            + meta.contentType
                            + " size="
                            + meta.contentLength
                            + " elapsedMs="
                            + (System.currentTimeMillis() - start));
            return result;
        } catch (Exception e) {
            log.warning("read_resource.reject url=" + safe(path) + " message=" + e.getMessage());
            return ReadResult.failure(path, "UNREACHABLE", e.getMessage());
        }
    }

    private ResponseMeta head(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(options.getTimeoutMillis());
        connection.setReadTimeout(options.getTimeoutMillis());
        connection.setRequestMethod("HEAD");
        connection.setRequestProperty("User-Agent", "AgentForge-ReadTool/1.0");
        int status = connection.getResponseCode();
        String contentType = connection.getContentType();
        long contentLength = contentLength(connection);
        connection.disconnect();
        if (status >= 200 && status < 400) {
            return new ResponseMeta(status, contentType, contentLength < 0 ? null : contentLength);
        }
        throw new IOException("HTTP " + status);
    }

    private static long contentLength(HttpURLConnection connection) {
        String raw = connection.getHeaderField("Content-Length");
        if (raw == null || raw.trim().isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static ReadResourceKind kind(String contentType, String path) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            return ReadResourceKind.IMAGE;
        }
        if (type.startsWith("text/html") || type.startsWith("application/xhtml")) {
            return ReadResourceKind.LINK;
        }
        if (type.startsWith("text/")) {
            return ReadResourceKind.TEXT;
        }
        String ext = extension(path);
        if ("png".equals(ext)
                || "jpg".equals(ext)
                || "jpeg".equals(ext)
                || "gif".equals(ext)
                || "webp".equals(ext)
                || "bmp".equals(ext)) {
            return ReadResourceKind.IMAGE;
        }
        if ("html".equals(ext) || "htm".equals(ext)) {
            return ReadResourceKind.LINK;
        }
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)) {
            return ReadResourceKind.TEXT;
        }
        return ReadResourceKind.UNKNOWN;
    }

    private static String name(URL url) {
        String path = url.getPath();
        if (path != null && !path.isEmpty()) {
            int index = path.lastIndexOf('/');
            String name = index >= 0 ? path.substring(index + 1) : path;
            if (!name.isEmpty()) {
                return name;
            }
        }
        return url.getHost();
    }

    private static String extension(String path) {
        if (path == null) {
            return null;
        }
        try {
            String uriPath = new URI(path).getPath();
            int index = uriPath == null ? -1 : uriPath.lastIndexOf('.');
            if (index >= 0 && index < uriPath.length() - 1) {
                return uriPath.substring(index + 1).toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static boolean isPublicHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(value) || value.endsWith(".localhost")) {
            return false;
        }
        return !(LOOPBACK_127.matcher(value).matches()
                || PRIVATE_10.matcher(value).matches()
                || PRIVATE_172.matcher(value).matches()
                || PRIVATE_192.matcher(value).matches());
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        int index = value.indexOf('?');
        return index >= 0 ? value.substring(0, index) + "?..." : value;
    }

    private static final class ResponseMeta {
        private final int status;
        private final String contentType;
        private final Long contentLength;

        private ResponseMeta(int status, String contentType, Long contentLength) {
            this.status = status;
            this.contentType = contentType;
            this.contentLength = contentLength;
        }
    }
}
