package cloud.changlu.agentforge.service.modules.trace.dao;

import cloud.changlu.agentforge.service.modules.trace.domain.AgentTraceSnapshotPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentTraceSnapshotMapper {
    int insert(AgentTraceSnapshotPojo pojo);

    int updateSnapshot(AgentTraceSnapshotPojo pojo);

    Integer countFinishedBySessionId(@Param("sessionId") String sessionId);

    List<AgentTraceSnapshotPojo> selectBySessionId(@Param("sessionId") String sessionId);

    List<AgentTraceSnapshotPojo> selectRecentSessions(
            @Param("aid") String aid, @Param("debug") Integer debug, @Param("limit") Integer limit);
}
