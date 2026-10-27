package cloud.changlu.agentforge.service.modules.agent.dao;

import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPublishedPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Agent 发布快照 Mapper。 */
@Mapper
public interface AgentConfigPublishedMapper {

    AgentConfigPublishedPojo selectByAid(@Param("aid") String aid);

    int insert(AgentConfigPublishedPojo pojo);

    int deleteByAid(@Param("aid") String aid);
}
