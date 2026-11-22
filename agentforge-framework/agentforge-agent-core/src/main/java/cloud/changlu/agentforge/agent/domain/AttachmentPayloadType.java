package cloud.changlu.agentforge.agent.domain;

/**
 * 附件真正下发给模型时的载荷形态。
 *
 * @author changlu
 * @since 2026-11-21
 */
public enum AttachmentPayloadType {

    /** 直接把外链交给模型。 */
    URL,

    /** 本地读文件转 Base64 内联。 */
    INLINE,

    /** 退化为文字引用。 */
    TEXT
}
