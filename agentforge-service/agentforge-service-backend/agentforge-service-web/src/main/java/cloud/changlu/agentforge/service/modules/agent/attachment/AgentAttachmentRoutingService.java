package cloud.changlu.agentforge.service.modules.agent.attachment;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;

/**
 * 附件载荷路由：按模型能力与 URL 可达性决定下发给模型的形态。
 *
 * @author changlu
 * @since 2026-11-21
 */
public interface AgentAttachmentRoutingService {

    /**
     * 决策附件载荷形态。
     *
     * @param attachmentType 附件业务分类
     * @param visitUrl 可直接访问的地址
     * @param fileSize 文件字节数
     * @param modelConfig 模型能力配置
     * @param providerCode 模型协议码，Anthropic 不支持外链图片
     * @return 载荷形态
     */
    AttachmentPayloadType route(
            AgentAttachmentType attachmentType,
            String visitUrl,
            long fileSize,
            LlmModelConfig modelConfig,
            Integer providerCode);
}
