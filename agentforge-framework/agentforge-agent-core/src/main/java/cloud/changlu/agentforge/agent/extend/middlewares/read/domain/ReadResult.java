package cloud.changlu.agentforge.agent.extend.middlewares.read.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个读取资源的元数据。只承载描述信息，不承载图片字节或 Content。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ReadResult {

    private boolean ok = true;
    private String errorCode;
    private String message;
    private ReadResourceKind kind = ReadResourceKind.UNKNOWN;
    private String url;
    private String name;
    private String mimeType;
    private String fileType;
    private Long size;
    private String source;
    private String text;
    private String dedupeKey;
    private Map<String, Object> extra = new LinkedHashMap<String, Object>();

    public static ReadResult failure(String url, String errorCode, String message) {
        ReadResult result = new ReadResult();
        result.setOk(false);
        result.setUrl(url);
        result.setErrorCode(errorCode);
        result.setMessage(message);
        return result;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public ReadResourceKind getKind() {
        return kind;
    }

    public void setKind(ReadResourceKind kind) {
        this.kind = kind == null ? ReadResourceKind.UNKNOWN : kind;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getDedupeKey() {
        if (dedupeKey != null && !dedupeKey.trim().isEmpty()) {
            return dedupeKey;
        }
        return url;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra == null ? new LinkedHashMap<String, Object>() : extra;
    }
}
