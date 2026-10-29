package cloud.changlu.agentforge.service.modules.session.service;

import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.service.modules.session.domain.vo.SessionMessageVo;
import cloud.changlu.agentforge.service.modules.session.domain.vo.SessionVo;

import java.util.List;

/** Agent 会话与消息服务。 */
public interface IAgentSessionService {

    /**
     * 聊天请求初始化：解析/创建会话，写入 USER 消息与 AI 占位消息。
     *
     * @return 初始化结果，包含会话主键与 AI 消息主键
     */
    SessionInitResult initChat(
            String aid, String sessionId, String message, Long createUserId, boolean debug);

    /** 普通历史会话列表（未置顶）。 */
    List<SessionVo> listNormal(String aid, Long createUserId, Boolean debug, Integer limit);

    /** 置顶会话列表。 */
    List<SessionVo> listTop(String aid, Long createUserId);

    /** 添加或取消置顶。 */
    boolean setTop(Long sessionId, Long createUserId, boolean top);

    /** 修改会话标题。 */
    SessionVo changeTitle(Long sessionId, Long createUserId, String title);

    /** 逻辑删除会话及其消息。 */
    boolean delete(Long sessionId, Long createUserId);

    /** 会话消息明细，按 seq_num 升序。 */
    List<SessionMessageVo> listMessages(Long sessionId, Long createUserId);

    AgentSessionPojo requireOwned(Long sessionId, Long createUserId);

    /** 请求初始化结果。 */
    final class SessionInitResult {
        private final String sessionId;
        private final Long sessionPk;
        private final Long aiMessageId;
        private final Integer seqNum;
        private final String title;

        public SessionInitResult(
                String sessionId, Long sessionPk, Long aiMessageId, Integer seqNum, String title) {
            this.sessionId = sessionId;
            this.sessionPk = sessionPk;
            this.aiMessageId = aiMessageId;
            this.seqNum = seqNum;
            this.title = title;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Long getSessionPk() {
            return sessionPk;
        }

        public Long getAiMessageId() {
            return aiMessageId;
        }

        public Integer getSeqNum() {
            return seqNum;
        }

        public String getTitle() {
            return title;
        }
    }
}
