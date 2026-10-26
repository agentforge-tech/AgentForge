package cloud.changlu.agentforge.service.modules.session.dao;

import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionMessagePojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** Agent 会话消息 Mapper，对应 {@code ai_agent_session_message}。 */
@Mapper
public interface AgentSessionMessageMapper {

    int insert(AgentSessionMessagePojo pojo);

    AgentSessionMessagePojo selectById(@Param("id") Long id);

    List<AgentSessionMessagePojo> selectBySessionId(@Param("sessionId") Long sessionId);

    Integer selectMaxSeqNum(@Param("sessionId") Long sessionId);

    int updateStatus(
            @Param("id") Long id,
            @Param("status") Integer status,
            @Param("modifyTime") Date modifyTime);

    int updateStarted(
            @Param("id") Long id,
            @Param("status") Integer status,
            @Param("startedAt") Date startedAt,
            @Param("modifyTime") Date modifyTime);

    /** 一次性完成收口：正文、事件、耗时与终态。 */
    int finish(
            @Param("id") Long id,
            @Param("status") Integer status,
            @Param("content") String content,
            @Param("events") String events,
            @Param("elapsedMs") Long elapsedMs,
            @Param("finishedAt") Date finishedAt,
            @Param("modifyTime") Date modifyTime);

    int logicDeleteBySessionId(
            @Param("sessionId") Long sessionId, @Param("modifyTime") Date modifyTime);

    /** 会话下未删除消息数量。 */
    int countBySessionId(@Param("sessionId") Long sessionId);

    /** 会话下最后一条未删除消息时间。 */
    Date selectLastMessageTime(@Param("sessionId") Long sessionId);
}
