package cloud.changlu.agentforge.service.modules.upload.loader;

import cloud.changlu.agentforge.agent.domain.ImageLoader;
import cloud.changlu.agentforge.service.modules.upload.config.LocalUploadProperties;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地优先的图片加载器。
 *
 * <p>{@code visitHost} 默认是 {@code 127.0.0.1}，服务自己请求自己会额外消耗一次 HTTP 往返和一次文件 IO；直接读磁盘更快，
 * 也避免了「反向代理没配好导致自请求失败」的环境问题。非本服务地址才走网络下载。
 *
 * @author changlu
 * @since 2026-11-21
 */
@Component
public class LocalFirstImageLoader implements ImageLoader {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final Map<String, String> EXT_MIME;

    static {
        Map<String, String> mime = new HashMap<String, String>();
        mime.put("png", "image/png");
        mime.put("jpg", "image/jpeg");
        mime.put("jpeg", "image/jpeg");
        mime.put("gif", "image/gif");
        mime.put("webp", "image/webp");
        mime.put("bmp", "image/bmp");
        EXT_MIME = Collections.unmodifiableMap(mime);
    }

    private final LocalUploadProperties uploadProperties;

    public LocalFirstImageLoader(LocalUploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public ImageBinary load(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("image url must not be blank");
        }
        // 本地文件优先：同一台机器上传的图片无需走 HTTP
        Path local = uploadProperties.resolveLocalPath(url);
        if (local != null && Files.isRegularFile(local)) {
            try {
                long size = Files.size(local);
                if (size > MAX_BYTES) {
                    throw new IllegalStateException("image exceeds " + MAX_BYTES + " bytes");
                }
                return new ImageBinary(Files.readAllBytes(local), detectMime(local));
            } catch (IOException e) {
                throw new IllegalStateException("failed to read local image: " + local, e);
            }
        }
        return download(url);
    }

    private ImageBinary download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url.trim()).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "AgentForge/1.0");
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("download failed, status=" + status);
            }
            byte[] body = readBody(connection.getInputStream());
            if (body.length == 0) {
                throw new IllegalStateException("download failed, empty body");
            }
            return new ImageBinary(body, normalizeMime(connection.getContentType()));
        } catch (IOException e) {
            throw new IllegalStateException("download image failed: " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readBody(InputStream input) throws IOException {
        try (InputStream in = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IllegalStateException("image exceeds " + MAX_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String detectMime(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        String mime = EXT_MIME.get(ext);
        return mime == null ? "image/png" : mime;
    }

    private String normalizeMime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "image/png";
        }
        int semicolon = raw.indexOf(';');
        String mime = semicolon >= 0 ? raw.substring(0, semicolon) : raw;
        mime = mime.trim().toLowerCase(Locale.ROOT);
        return mime.startsWith("image/") ? mime : "image/png";
    }
}
