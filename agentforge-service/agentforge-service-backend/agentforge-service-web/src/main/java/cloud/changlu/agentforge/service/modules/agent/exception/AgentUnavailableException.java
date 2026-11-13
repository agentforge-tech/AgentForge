package cloud.changlu.agentforge.service.modules.agent.exception;

import cloud.changlu.agentforge.service.common.exception.AgentForgeException;

/**
 * Agent 当前不可用（未发布、已停止发布或已删除）时抛出。
 *
 * <p>消息内容直接面向终端用户，对话接口会把它作为一条正常 AI 回答返回，而不是抛出 500 异常。
 */
public class AgentUnavailableException extends AgentForgeException {

    /** 业务错误码：Agent 不可用 */
    public static final String CODE = "AGENT_UNAVAILABLE";

    public AgentUnavailableException(String message) {
        super(CODE, message);
    }
}
