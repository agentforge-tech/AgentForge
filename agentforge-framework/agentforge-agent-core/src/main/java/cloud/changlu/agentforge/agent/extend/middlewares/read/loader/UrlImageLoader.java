package cloud.changlu.agentforge.agent.extend.middlewares.read.loader;

import cloud.changlu.agentforge.agent.domain.ImageLoader;
import cloud.changlu.agentforge.agent.extend.middlewares.read.tool.ReadToolOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 无状态 URL 图片加载器：直接读取远程 URL 字节，不依赖上传目录。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class UrlImageLoader implements ImageLoader {

    private final int timeoutMillis;
    private final long maxBytes;

    public UrlImageLoader() {
        this(ReadToolOptions.defaults());
    }

    public UrlImageLoader(ReadToolOptions options) {
        ReadToolOptions effective = options == null ? ReadToolOptions.defaults() : options;
        this.timeoutMillis = effective.getTimeoutMillis();
        this.maxBytes = effective.getMaxImageBytes();
    }

    @Override
    public ImageBinary load(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", "AgentForge-ReadTool/1.0");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            String mimeType = connection.getContentType();
            byte[] bytes = readLimited(connection.getInputStream());
            return new ImageBinary(bytes, mimeType == null ? sniff(url) : mimeType);
        } catch (IOException e) {
            throw new IllegalStateException("load image failed: " + url, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        try (InputStream source = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("image exceeds max bytes: " + maxBytes);
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sniff(String url) {
        String value = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (value.endsWith(".png")) {
            return "image/png";
        }
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (value.endsWith(".gif")) {
            return "image/gif";
        }
        if (value.endsWith(".webp")) {
            return "image/webp";
        }
        if (value.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "application/octet-stream";
    }
}
