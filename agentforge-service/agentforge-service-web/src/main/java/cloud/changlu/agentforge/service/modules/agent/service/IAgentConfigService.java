package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.PublishedAgentBasicVo;

import java.util.List;

/** Agent 配置服务。 */
public interface IAgentConfigService {

    PageResult<List<AgentConfigVo>> page(AgentConfigQueryReq req);

    AgentConfigVo getById(Long id);

    PublishedAgentBasicVo getPublishedBasicByAid(String aid);

    AgentConfigVo upsert(AgentConfigUpsertReq req);

    AgentConfigVo updatePublishStatus(Long id, boolean published);

    void delete(Long id);
}
