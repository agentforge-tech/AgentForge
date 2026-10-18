package cloud.changlu.agentforge.service.modules.model.service;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigQueryReq;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigTestReq;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigDetailVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigOptionsVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigTestVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigVo;

import java.util.List;

/**
 * LLM 模型配置 Service。
 *
 * @author changlu
 * @since 2026-10-14
 */
public interface IModelConfigService {

    /** 新增或更新模型配置（{@code id} 为空新增，否则更新），返回保存后的模型。 */
    ModelConfigVo upsert(ModelConfigUpsertReq req);

    /** 按主键查询模型配置。 */
    ModelConfigDetailVo getById(Long id);

    /** 按条件查询模型配置列表。 */
    List<ModelConfigVo> list(ModelConfigQueryReq req);

    /** 按条件分页查询模型配置。 */
    PageResult<List<ModelConfigVo>> page(ModelConfigQueryReq req);

    /** 按主键逻辑删除模型配置。 */
    void delete(Long id);

    /** 测试模型连通性。 */
    ModelConfigTestVo testConnectivity(ModelConfigTestReq req);

    /** 测试模型是否支持 Function Call。 */
    ModelConfigTestVo testFunctionCall(ModelConfigTestReq req);

    /** 获取模型厂商与模型类别枚举选项。 */
    ModelConfigOptionsVo options();
}
