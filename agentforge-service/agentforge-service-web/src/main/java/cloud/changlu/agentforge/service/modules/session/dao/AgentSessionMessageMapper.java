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
            @Param("executionId") String executionId,
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

    /** 停止受理：仅当消息仍为 CREATED(1)/RUNNING(2) 时标记为 CANCELLING(7)，用于幂等。 */
    int markCancelling(
            @Param("id") Long id,
            @Param("status") Integer status,
            @Param("modifyTime") Date modifyTime);

    /** 停止等待超时兜底：仅当消息仍为 CREATED(1)/RUNNING(2)/CANCELLING(7) 时收口为超时取消。 */
    int timeoutCancelIfRunning(
            @Param("id") Long id,
            @Param("status") Integer status,
            @Param("elapsedMs") Long elapsedMs,
            @Param("finishedAt") Date finishedAt,
            @Param("modifyTime") Date modifyTime);

    /** 运行中增量写入事件快照，供其他页面恢复当前 Agent Run 的页面状态。 */
    int updateEvents(
            @Param("id") Long id,
            @Param("events") String events,
            @Param("modifyTime") Date modifyTime);

    int logicDeleteBySessionId(
            @Param("sessionId") Long sessionId, @Param("modifyTime") Date modifyTime);

    /** 会话下未删除消息数量。 */
    int countBySessionId(@Param("sessionId") Long sessionId);

    /** 会话下最后一条未删除消息时间。 */
    Date selectLastMessageTime(@Param("sessionId") Long sessionId);
}
