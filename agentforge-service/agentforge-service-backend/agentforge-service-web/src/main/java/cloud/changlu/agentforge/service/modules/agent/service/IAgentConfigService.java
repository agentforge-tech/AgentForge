package cloud.changlu.agentforge.service.modules.agent.service;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVersionVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.PublishedAgentBasicVo;

import java.util.List;

/** Agent 配置服务。 */
public interface IAgentConfigService {

    PageResult<List<AgentConfigVo>> page(AgentConfigQueryReq req);

    AgentConfigVo getById(Long id);

    PublishedAgentBasicVo getPublishedBasicByAid(String aid);

    List<AgentConfigVersionVo> listVersions(Long id);

    AgentConfigVersionVo getVersion(Long id, Long versionNo);

    AgentConfigVo upsert(AgentConfigUpsertReq req);

    AgentConfigVo updatePublishStatus(Long id, boolean published);

    /**
     * 读取历史版本配置，用于回填编辑页表单。
     *
     * <p>只读操作：不更新 {@code ai_agent_config}，不影响调试实例和发布快照。
     */
    AgentConfigVersionVo switchVersion(Long id, Long versionNo);

    /**
     * 更新历史版本备注。
     *
     * <p>版本快照本身不可修改，只允许补充或修改 {@code version_remark} 说明字段。
     */
    AgentConfigVersionVo updateVersionRemark(Long id, Long versionNo, String versionRemark);

    void delete(Long id);
}
