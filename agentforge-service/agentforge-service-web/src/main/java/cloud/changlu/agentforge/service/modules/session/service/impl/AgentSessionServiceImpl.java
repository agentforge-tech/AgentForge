package cloud.changlu.agentforge.service.modules.session.service.impl;

import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.service.modules.session.domain.vo.SessionMessageVo;
import cloud.changlu.agentforge.service.modules.session.domain.vo.SessionVo;
import cloud.changlu.agentforge.service.modules.session.enums.SessionDebugEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageRoleEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.service.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.service.modules.session.service.IAgentSessionService;
import cloud.changlu.agentforge.service.modules.session.util.SessionTitleUtil;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Agent 会话与消息服务实现。 */
@Service
public class AgentSessionServiceImpl implements IAgentSessionService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int PIN_NONE = 0;

    private final AgentSessionMapper agentSessionMapper;
    private final AgentSessionMessageMapper agentSessionMessageMapper;

    public AgentSessionServiceImpl(
            AgentSessionMapper agentSessionMapper,
            AgentSessionMessageMapper agentSessionMessageMapper) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentSessionMessageMapper = agentSessionMessageMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionInitResult initChat(
            String aid, String sessionId, String message, Long createUserId, boolean debug) {
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        String trimmedAid = aid.trim();
        Long userId = createUserId == null ? 1L : createUserId;
        Date now = new Date();

        String resolvedSessionId =
                sessionId == null || sessionId.trim().isEmpty()
                        ? UUID.randomUUID().toString()
                        : sessionId.trim();

        AgentSessionPojo session =
                agentSessionMapper.selectBySessionIdAndAid(resolvedSessionId, trimmedAid);
        if (session == null) {
            session = new AgentSessionPojo();
            session.setSessionId(resolvedSessionId);
            session.setAid(trimmedAid);
            session.setTitle(SessionTitleUtil.resolve(message));
            session.setStatus(SessionStatusEnum.IDLE.getCode());
            session.setIsDebug(
                    debug ? SessionDebugEnum.DEBUG.getCode() : SessionDebugEnum.FORMAL.getCode());
            session.setPinOrder(PIN_NONE);
            session.setCreateUserId(userId);
            agentSessionMapper.insert(session);
        } else {
            agentSessionMapper.touch(resolvedSessionId, now);
        }

        Integer maxSeq = agentSessionMessageMapper.selectMaxSeqNum(session.getId());
        int userSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        int aiSeq = userSeq + 1;

        AgentSessionMessagePojo userMessage = new AgentSessionMessagePojo();
        userMessage.setSessionId(session.getId());
        userMessage.setSeqNum(userSeq);
        userMessage.setRoleType(SessionMessageRoleEnum.USER.getCode());
        userMessage.setStatus(SessionMessageStatusEnum.SUCCEEDED.getCode());
        userMessage.setContent(message);
        userMessage.setCreateUserId(userId);
        agentSessionMessageMapper.insert(userMessage);

        AgentSessionMessagePojo aiMessage = new AgentSessionMessagePojo();
        aiMessage.setSessionId(session.getId());
        aiMessage.setSeqNum(aiSeq);
        aiMessage.setRoleType(SessionMessageRoleEnum.AI.getCode());
        aiMessage.setStatus(SessionMessageStatusEnum.CREATED.getCode());
        aiMessage.setCreateUserId(userId);
        agentSessionMessageMapper.insert(aiMessage);

        return new SessionInitResult(
                resolvedSessionId, session.getId(), aiMessage.getId(), aiSeq, session.getTitle());
    }

    @Override
    public List<SessionVo> listNormal(String aid, Long createUserId, Boolean debug, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Integer debugFilter =
                debug == null
                        ? null
                        : (debug
                                ? SessionDebugEnum.DEBUG.getCode()
                                : SessionDebugEnum.FORMAL.getCode());
        return toVoList(
                agentSessionMapper.selectNormalList(aid, userId(createUserId), debugFilter, size));
    }

    @Override
    public List<SessionVo> listTop(String aid, Long createUserId) {
        return toVoList(agentSessionMapper.selectTopList(aid, userId(createUserId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setTop(Long sessionId, Long createUserId, boolean top) {
        AgentSessionPojo session = requireOwned(sessionId, createUserId);
        Date now = new Date();
        if (!top) {
            agentSessionMapper.updatePinOrder(session.getId(), PIN_NONE, now);
            return true;
        }
        Integer max = agentSessionMapper.selectMaxPinOrder(userId(createUserId));
        int next = (max == null ? 0 : max) + 1;
        agentSessionMapper.updatePinOrder(session.getId(), next, now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionVo changeTitle(Long sessionId, Long createUserId, String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("会话标题不能为空");
        }
        AgentSessionPojo session = requireOwned(sessionId, createUserId);
        String trimmed = title.trim();
        agentSessionMapper.updateTitle(session.getId(), trimmed, new Date());
        session.setTitle(trimmed);
        return toVo(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long sessionId, Long createUserId) {
        AgentSessionPojo session = requireOwned(sessionId, createUserId);
        Date now = new Date();
        agentSessionMapper.logicDelete(session.getId(), now);
        agentSessionMessageMapper.logicDeleteBySessionId(session.getId(), now);
        return true;
    }

    @Override
    public List<SessionMessageVo> listMessages(Long sessionId, Long createUserId) {
        AgentSessionPojo session = requireOwned(sessionId, createUserId);
        List<AgentSessionMessagePojo> messages =
                agentSessionMessageMapper.selectBySessionId(session.getId());
        List<SessionMessageVo> result = new ArrayList<>(messages.size());
        for (AgentSessionMessagePojo message : messages) {
            result.add(toMessageVo(session, message));
        }
        return result;
    }

    @Override
    public AgentSessionPojo requireOwned(Long sessionId, Long createUserId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        AgentSessionPojo session = agentSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在或已被删除");
        }
        if (!userId(createUserId).equals(session.getCreateUserId())) {
            throw new IllegalArgumentException("无权操作该会话");
        }
        return session;
    }

    private List<SessionVo> toVoList(List<AgentSessionPojo> sessions) {
        List<SessionVo> result = new ArrayList<>(sessions.size());
        for (AgentSessionPojo session : sessions) {
            result.add(toVo(session));
        }
        return result;
    }

    private SessionVo toVo(AgentSessionPojo session) {
        SessionVo vo = new SessionVo();
        vo.setId(session.getId());
        vo.setSessionId(session.getSessionId());
        vo.setAid(session.getAid());
        vo.setTitle(session.getTitle());
        vo.setStatus(session.getStatus());
        vo.setIsDebug(session.getIsDebug());
        vo.setTopOrder(session.getPinOrder());
        vo.setCreateUserId(session.getCreateUserId());
        vo.setCreateTime(session.getCreateTime());
        vo.setModifyTime(session.getModifyTime());
        vo.setRunning(SessionStatusEnum.RUNNING.getCode().equals(session.getStatus()));
        vo.setMessageCount(agentSessionMessageMapper.countBySessionId(session.getId()));
        vo.setLastMessageAt(agentSessionMessageMapper.selectLastMessageTime(session.getId()));
        return vo;
    }

    private SessionMessageVo toMessageVo(
            AgentSessionPojo session, AgentSessionMessagePojo message) {
        SessionMessageVo vo = new SessionMessageVo();
        vo.setId(message.getId());
        vo.setSessionRefId(message.getSessionId());
        vo.setSessionId(session.getSessionId());
        vo.setSeqNum(message.getSeqNum());
        vo.setRoleType(message.getRoleType());
        vo.setStatus(message.getStatus());
        vo.setStartedAt(message.getStartedAt());
        vo.setFinishedAt(message.getFinishedAt());
        vo.setElapsedMs(message.getElapsedMs());
        vo.setContentText(message.getContent());
        vo.setEvents(message.getEvents());
        vo.setIsDeleted(message.getIsDeleted());
        vo.setCreateUserId(message.getCreateUserId());
        vo.setCreateTime(message.getCreateTime());
        vo.setModifyTime(message.getModifyTime());
        return vo;
    }

    private static Long userId(Long createUserId) {
        return createUserId == null ? 1L : createUserId;
    }
}
