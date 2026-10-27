package cloud.changlu.agentforge.service.modules.agent.dao;

import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigVersionPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Agent 历史版本 Mapper。 */
@Mapper
public interface AgentConfigVersionMapper {

    List<AgentConfigVersionPojo> selectByAid(@Param("aid") String aid);

    AgentConfigVersionPojo selectByAidAndVersionNo(
            @Param("aid") String aid, @Param("versionNo") Long versionNo);

    Long selectMaxVersionNo(@Param("aid") String aid);

    int insert(AgentConfigVersionPojo pojo);

    /** 仅更新版本备注，版本快照其余字段保持不可变。 */
    int updateRemark(
            @Param("id") Long id,
            @Param("versionRemark") String versionRemark,
            @Param("modifyTime") java.util.Date modifyTime);
}
