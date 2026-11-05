package cloud.changlu.agentforge.service.modules.agent.dao;

import cloud.changlu.agentforge.service.modules.agent.domain.pojo.UserMessageAppendPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 持久化追加消息队列。 */
@Mapper
public interface UserMessageAppendMapper {
    int insert(UserMessageAppendPojo pojo);

    List<UserMessageAppendPojo> selectPending(
            @Param("sessionId") String sessionId, @Param("status") int status);

    UserMessageAppendPojo selectPendingById(
            @Param("sessionId") String sessionId,
            @Param("id") Long id,
            @Param("status") int status);

    int countPending(@Param("sessionId") String sessionId, @Param("status") int status);

    int deleteByIds(@Param("ids") List<Long> ids);

    int deletePendingById(
            @Param("sessionId") String sessionId,
            @Param("id") Long id,
            @Param("status") int status);

    int deleteBySessionId(@Param("sessionId") String sessionId);
}
