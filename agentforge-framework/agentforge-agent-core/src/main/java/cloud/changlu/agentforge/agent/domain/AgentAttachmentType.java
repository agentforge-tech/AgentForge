package cloud.changlu.agentforge.agent.domain;

/**
 * 附件业务分类。
 *
 * @author changlu
 * @since 2026-11-21
 */
public enum AgentAttachmentType {

    /** 图片附件，可进入多模态图片通道。 */
    IMAGE,

    /** 文本类附件，读取正文拼进文本块。 */
    TEXT,

    /** 未知类型，只给元信息引用。 */
    UNKNOWN
}
