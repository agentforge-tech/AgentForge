package cloud.changlu.agentforge.service.modules.agent.dao;

import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Agent 配置 Mapper。 */
@Mapper
public interface AgentConfigMapper {

    AgentConfigPojo selectById(@Param("id") Long id);

    AgentConfigPojo selectByAid(@Param("aid") String aid);

    long selectCount(AgentConfigQueryReq query);

    List<AgentConfigPojo> selectPage(AgentConfigQueryReq query);

    int insert(AgentConfigPojo pojo);

    int update(AgentConfigPojo pojo);

    int updatePublishStatus(
            @Param("id") Long id,
            @Param("publish") Integer publish,
            @Param("modifyTime") java.util.Date modifyTime);

    int deleteById(@Param("id") Long id);
}
