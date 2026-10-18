package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVo;

import java.util.List;

/** Agent 配置服务。 */
public interface IAgentConfigService {

    PageResult<List<AgentConfigVo>> page(AgentConfigQueryReq req);

    AgentConfigVo getById(Long id);

    AgentConfigVo upsert(AgentConfigUpsertReq req);

    void delete(Long id);
}
