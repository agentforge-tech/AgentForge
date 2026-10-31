package cloud.changlu.agentforge.service.modules.session.dao;

import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** Agent 会话 Mapper，对应 {@code ai_agent_session}。 */
@Mapper
public interface AgentSessionMapper {

    int insert(AgentSessionPojo pojo);

    AgentSessionPojo selectById(@Param("id") Long id);

    AgentSessionPojo selectBySessionId(@Param("sessionId") String sessionId);

    AgentSessionPojo selectBySessionIdAndAid(
            @Param("sessionId") String sessionId, @Param("aid") String aid);

    /** 普通历史会话：未置顶，按最近修改时间倒序；debug 为空表示不区分调试/发布态。 */
    List<AgentSessionPojo> selectNormalList(
            @Param("aid") String aid,
            @Param("createUserId") Long createUserId,
            @Param("debug") Integer debug,
            @Param("limit") Integer limit);

    /** 置顶会话：按置顶序号倒序全量返回。 */
    List<AgentSessionPojo> selectTopList(
            @Param("aid") String aid, @Param("createUserId") Long createUserId);

    /** 当前用户已有置顶最大序号，无置顶返回 null。 */
    Integer selectMaxPinOrder(@Param("createUserId") Long createUserId);

    int updateStatus(
            @Param("id") Long id,
            @Param("status") Integer status,
            @Param("modifyTime") Date modifyTime);

    int updateStatusBySessionId(
            @Param("sessionId") String sessionId,
            @Param("status") Integer status,
            @Param("modifyTime") Date modifyTime);

    int updateTitle(
            @Param("id") Long id,
            @Param("title") String title,
            @Param("modifyTime") Date modifyTime);

    int updatePinOrder(
            @Param("id") Long id,
            @Param("pinOrder") Integer pinOrder,
            @Param("modifyTime") Date modifyTime);

    int touch(@Param("sessionId") String sessionId, @Param("modifyTime") Date modifyTime);

    int logicDelete(@Param("id") Long id, @Param("modifyTime") Date modifyTime);
}
