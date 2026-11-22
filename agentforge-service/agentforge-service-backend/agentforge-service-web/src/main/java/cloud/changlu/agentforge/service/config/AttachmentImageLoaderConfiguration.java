package cloud.changlu.agentforge.service.config;

import cloud.changlu.agentforge.agent.support.AttachmentContentAssembler;
import cloud.changlu.agentforge.service.modules.upload.loader.LocalFirstImageLoader;

import org.springframework.context.annotation.Configuration;

/**
 * 把业务层的本地优先图片加载器注册为附件装配器的全局默认实现。
 *
 * <p>Agent 与中间件不再各自注入 {@code ImageLoader}，统一由 {@link AttachmentContentAssembler} 内置默认值；service
 * 侧只需在启动时注册一次本地优先实现，用户上传附件与 Read Tool 读取图片即可共享同一套加载逻辑。
 *
 * @author changlu
 * @since 2026-11-22
 */
@Configuration
public class AttachmentImageLoaderConfiguration {

    public AttachmentImageLoaderConfiguration(LocalFirstImageLoader localFirstImageLoader) {
        AttachmentContentAssembler.setDefaultImageLoader(localFirstImageLoader);
    }
}
