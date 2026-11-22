package cloud.changlu.agentforge.agent.domain;

/**
 * 图片二进制加载抽象，隔离框架层与具体存储实现。
 *
 * <p>框架层只负责把附件声明为内联形态，真正的读取由业务层实现（本地磁盘优先、必要时走网络）。
 *
 * @author changlu
 * @since 2026-11-21
 */
public interface ImageLoader {

    /**
     * 按访问地址加载图片二进制。
     *
     * @param url 可直接访问的图片地址
     * @return 图片二进制与 MIME 类型
     */
    ImageBinary load(String url);

    /** 图片二进制与 MIME 类型。 */
    final class ImageBinary {
        private final byte[] bytes;
        private final String mimeType;

        public ImageBinary(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }

        public byte[] bytes() {
            return bytes;
        }

        public String mimeType() {
            return mimeType;
        }

        public String base64() {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
    }
}
