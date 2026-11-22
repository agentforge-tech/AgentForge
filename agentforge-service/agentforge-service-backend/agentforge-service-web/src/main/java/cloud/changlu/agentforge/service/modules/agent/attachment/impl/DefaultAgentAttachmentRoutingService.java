package cloud.changlu.agentforge.service.modules.agent.attachment.impl;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import cloud.changlu.agentforge.service.modules.agent.attachment.AgentAttachmentRoutingService;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * 默认附件载荷路由。
 *
 * <p>决策顺序：模型不支持图片 → TEXT；非图片 → TEXT；公网可达且非 Anthropic → URL；体积在上限内 → INLINE；否则保守降级为 TEXT。
 *
 * @author changlu
 * @since 2026-11-21
 */
@Service
public class DefaultAgentAttachmentRoutingService implements AgentAttachmentRoutingService {

    /** 单张 INLINE 图片上限，超过则降级为文字引用，避免上下文被单图打满。 */
    private static final long MAX_INLINE_IMAGE_BYTES = 4L * 1024 * 1024;

    private static final Pattern PRIVATE_10 = Pattern.compile("^10\\..*");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[0-1])\\..*");
    private static final Pattern PRIVATE_192 = Pattern.compile("^192\\.168\\..*");
    private static final Pattern LOOPBACK_127 = Pattern.compile("^127\\..*");

    @Override
    public AttachmentPayloadType route(
            AgentAttachmentType attachmentType,
            String visitUrl,
            long fileSize,
            LlmModelConfig modelConfig,
            Integer providerCode) {
        // 1. 模型不支持图片：一律文字引用
        if (modelConfig == null || !modelConfig.isSupportsImage()) {
            return AttachmentPayloadType.TEXT;
        }
        // 2. 只有 IMAGE 才进入图片通道
        if (attachmentType != AgentAttachmentType.IMAGE) {
            return AttachmentPayloadType.TEXT;
        }
        // 3. Anthropic 不支持外链图片，只能内联
        boolean providerRequiresInline =
                providerCode != null && providerCode == LlmEnum.ANTHROPIC.getCode();
        if (!providerRequiresInline && isPubliclyAccessible(visitUrl)) {
            return AttachmentPayloadType.URL;
        }
        // 4. 需要内联时校验体积
        if (fileSize > 0 && fileSize <= MAX_INLINE_IMAGE_BYTES) {
            return AttachmentPayloadType.INLINE;
        }
        // 5. 体积超限或信息缺失：保守降级
        return AttachmentPayloadType.TEXT;
    }

    private boolean isPubliclyAccessible(String visitUrl) {
        if (visitUrl == null || visitUrl.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(visitUrl.trim());
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) {
                return false;
            }
            if ("localhost".equalsIgnoreCase(host)) {
                return false;
            }
            return !(LOOPBACK_127.matcher(host).matches()
                    || PRIVATE_10.matcher(host).matches()
                    || PRIVATE_172.matcher(host).matches()
                    || PRIVATE_192.matcher(host).matches());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
