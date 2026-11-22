package cloud.changlu.agentforge.agent.support;

import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.agent.domain.ImageLoader;
import cloud.changlu.agentforge.agent.extend.middlewares.read.loader.UrlImageLoader;
import cloud.changlu.agentforge.model.chat.message.Content;
import cloud.changlu.agentforge.model.chat.message.ImageContent;
import cloud.changlu.agentforge.model.chat.message.TextContent;
import cloud.changlu.agentforge.model.chat.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把 AgentAttachment 转换为模型可消费的 UserMessage(Content)。
 *
 * <p>用户直接上传附件与 Middleware 读取附件共用同一套装配规则。装配过程无状态，直接以静态方法使用； INLINE 图片默认由内置的无状态 {@link
 * UrlImageLoader} 读取，业务层如需本地优先可在启动时通过 {@link #setDefaultImageLoader(ImageLoader)} 注册一次全局实现。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class AttachmentContentAssembler {

    private static final Logger log = Logger.getLogger(AttachmentContentAssembler.class.getName());

    /** 框架内置的无状态 URL 图片加载器。 */
    private static final ImageLoader URL_IMAGE_LOADER = new UrlImageLoader();

    /** 全局默认图片加载器，业务层可在启动时覆盖为本地优先等实现。 */
    private static volatile ImageLoader defaultImageLoader = URL_IMAGE_LOADER;

    private AttachmentContentAssembler() {}

    /**
     * 注册全局默认图片加载器，只需在启动阶段设置一次。
     *
     * @param imageLoader 图片加载器；传 null 时回退到内置 {@link UrlImageLoader}
     */
    public static void setDefaultImageLoader(ImageLoader imageLoader) {
        defaultImageLoader = imageLoader == null ? URL_IMAGE_LOADER : imageLoader;
    }

    public static ImageLoader getDefaultImageLoader() {
        return defaultImageLoader;
    }

    public static UserMessage buildUserMessage(String text, List<AgentAttachment> attachments) {
        return buildUserMessage(text, attachments, defaultImageLoader);
    }

    public static UserMessage buildUserMessage(
            String text, List<AgentAttachment> attachments, ImageLoader imageLoader) {
        ImageLoader effective = imageLoader == null ? defaultImageLoader : imageLoader;
        List<Content> contents = new ArrayList<Content>();
        contents.add(
                TextContent.from(
                        text == null || text.trim().isEmpty() ? "我上传了附件，请结合附件内容回答。" : text.trim()));
        appendAttachments(contents, attachments, effective);
        return UserMessage.from(contents);
    }

    public static void appendAttachments(
            List<Content> contents, List<AgentAttachment> attachments) {
        appendAttachments(contents, attachments, defaultImageLoader);
    }

    public static void appendAttachments(
            List<Content> contents, List<AgentAttachment> attachments, ImageLoader imageLoader) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        ImageLoader effective = imageLoader == null ? defaultImageLoader : imageLoader;
        int index = 1;
        for (AgentAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            appendAttachment(contents, attachment, index++, effective);
        }
    }

    private static void appendAttachment(
            List<Content> contents,
            AgentAttachment attachment,
            int index,
            ImageLoader imageLoader) {
        String displayName = hasText(attachment.getName()) ? attachment.getName() : "未命名文件";

        if (hasText(attachment.getText())) {
            contents.add(
                    TextContent.from(
                            "附件" + index + "：" + displayName + "\n" + attachment.getText()));
            return;
        }

        if (!hasText(attachment.getUrl())) {
            contents.add(
                    TextContent.from(buildAttachmentReferenceText(attachment, index, displayName)));
            return;
        }

        if (attachment.getType() == AgentAttachmentType.IMAGE) {
            if (attachment.getPayloadType() == AttachmentPayloadType.INLINE
                    && appendInlineImage(contents, attachment, imageLoader)) {
                return;
            }
            if (attachment.getPayloadType() == AttachmentPayloadType.URL) {
                contents.add(
                        ImageContent.fromUrl(attachment.getUrl(), ImageContent.DetailLevel.AUTO));
                return;
            }
            if (appendInlineImage(contents, attachment, imageLoader)) {
                return;
            }
        }

        contents.add(
                TextContent.from(buildAttachmentReferenceText(attachment, index, displayName)));
    }

    private static boolean appendInlineImage(
            List<Content> contents, AgentAttachment attachment, ImageLoader imageLoader) {
        if (imageLoader == null) {
            return false;
        }
        try {
            ImageLoader.ImageBinary binary = imageLoader.load(attachment.getUrl());
            contents.add(ImageContent.fromBase64(binary.base64(), binary.mimeType()));
            return true;
        } catch (Exception e) {
            log.log(
                    Level.WARNING,
                    "[AttachmentContentAssembler] 图片转 Base64 失败，降级为文字引用 | name="
                            + attachment.getName()
                            + ", url="
                            + attachment.getUrl(),
                    e);
            return false;
        }
    }

    private static String buildAttachmentReferenceText(
            AgentAttachment attachment, int index, String displayName) {
        StringBuilder builder =
                new StringBuilder("附件").append(index).append("：").append(displayName);
        if (hasText(attachment.getFileType())) {
            builder.append("，类型 ").append(attachment.getFileType());
        }
        if (attachment.getSize() != null) {
            builder.append("，大小 ").append(attachment.getSize()).append(" bytes");
        }
        if (hasText(attachment.getUrl())) {
            builder.append("，访问地址 ").append(attachment.getUrl());
        }
        return builder.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
