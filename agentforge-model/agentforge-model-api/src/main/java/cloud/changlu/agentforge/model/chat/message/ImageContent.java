package cloud.changlu.agentforge.model.chat.message;

import java.util.Objects;

/**
 * 图片内容块，支持外链与 Base64 两种形态。
 *
 * <p>二选一：{@code url} 非空表示外链；{@code base64Data} + {@code mimeType} 非空表示内联。
 *
 * <p>该对象只承载数据，不承载下载逻辑，避免 model 层依赖 HTTP 客户端。
 *
 * @author changlu
 * @since 2026-11-21
 */
public final class ImageContent implements Content {

    /** 图片细节档位，对齐 OpenAI 的 detail 参数；AUTO 由模型自行决定。 */
    public enum DetailLevel {
        AUTO,
        LOW,
        HIGH
    }

    private final String url;
    private final String base64Data;
    private final String mimeType;
    private final DetailLevel detailLevel;

    private ImageContent(String url, String base64Data, String mimeType, DetailLevel detailLevel) {
        boolean hasUrl = url != null && !url.trim().isEmpty();
        boolean hasBase64 = base64Data != null && !base64Data.trim().isEmpty();
        if (!hasUrl && !hasBase64) {
            throw new IllegalArgumentException("ImageContent requires either url or base64Data");
        }
        if (hasBase64 && (mimeType == null || mimeType.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "ImageContent requires mimeType when base64Data is used");
        }
        this.url = hasUrl ? url.trim() : null;
        this.base64Data = hasBase64 ? base64Data : null;
        this.mimeType = mimeType == null ? null : mimeType.trim();
        this.detailLevel = detailLevel == null ? DetailLevel.AUTO : detailLevel;
    }

    /** 外链形态。 */
    public static ImageContent fromUrl(String url) {
        return new ImageContent(url, null, null, DetailLevel.AUTO);
    }

    /** 外链形态，指定细节档位。 */
    public static ImageContent fromUrl(String url, DetailLevel detailLevel) {
        return new ImageContent(url, null, null, detailLevel);
    }

    /** 内联形态，{@code base64Data} 不含 {@code data:} 前缀。 */
    public static ImageContent fromBase64(String base64Data, String mimeType) {
        return new ImageContent(null, base64Data, mimeType, DetailLevel.AUTO);
    }

    public boolean isUrl() {
        return url != null;
    }

    public boolean isInline() {
        return base64Data != null;
    }

    public String url() {
        return url;
    }

    public String base64Data() {
        return base64Data;
    }

    public String mimeType() {
        return mimeType;
    }

    public DetailLevel detailLevel() {
        return detailLevel;
    }

    /** 拼接为 data URI，供 OpenAI 协议直接使用。 */
    public String toDataUri() {
        return "data:" + mimeType + ";base64," + base64Data;
    }

    @Override
    public ContentType type() {
        return ContentType.IMAGE;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ImageContent other = (ImageContent) obj;
        return Objects.equals(url, other.url)
                && Objects.equals(base64Data, other.base64Data)
                && Objects.equals(mimeType, other.mimeType)
                && detailLevel == other.detailLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, base64Data, mimeType, detailLevel);
    }

    @Override
    public String toString() {
        return "ImageContent{"
                + (url != null
                        ? "url='" + url + '\''
                        : "base64=" + (base64Data == null ? 0 : base64Data.length()) + " chars")
                + ", mimeType='"
                + mimeType
                + '\''
                + ", detailLevel="
                + detailLevel
                + '}';
    }
}
