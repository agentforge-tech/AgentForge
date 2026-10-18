package cloud.changlu.agentforge.service.modules.model.domain.transfer;

import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigDetailVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigVo;
import cloud.changlu.agentforge.service.modules.model.enums.LlmModelTypeEnum;
import cloud.changlu.agentforge.service.modules.model.enums.LlmProviderEnum;

/**
 * LLM 模型配置对象转换工具。
 *
 * @author changlu
 * @since 2026-10-14
 */
public final class ModelConfigTransfer {

    private ModelConfigTransfer() {}

    public static ModelConfigPojo toPojo(ModelConfigUpsertReq req) {
        ModelConfigPojo pojo = new ModelConfigPojo();
        pojo.setId(req.getId());
        pojo.setName(req.getName());
        pojo.setProvider(req.getProvider());
        pojo.setModelType(req.getModelType());
        pojo.setModelname(req.getModelname());
        pojo.setUrl(req.getUrl());
        pojo.setApiKey(req.getApiKey());
        pojo.setCreateType(req.getCreateType());
        pojo.setCreateUserId(req.getCreateUserId());
        return pojo;
    }

    public static ModelConfigVo toVo(ModelConfigPojo pojo) {
        if (pojo == null) {
            return null;
        }
        ModelConfigVo vo = new ModelConfigVo();
        vo.setId(pojo.getId());
        vo.setName(pojo.getName());
        vo.setProvider(pojo.getProvider());
        vo.setProviderName(providerName(pojo.getProvider()));
        vo.setModelType(pojo.getModelType());
        vo.setModelTypeName(modelTypeName(pojo.getModelType()));
        vo.setModelname(pojo.getModelname());
        vo.setUrl(pojo.getUrl());
        vo.setHasApiKey(pojo.getApiKey() != null && !pojo.getApiKey().trim().isEmpty());
        vo.setCreateType(pojo.getCreateType());
        vo.setCreateUserId(pojo.getCreateUserId());
        vo.setCreateTime(pojo.getCreateTime());
        vo.setModifyTime(pojo.getModifyTime());
        return vo;
    }

    public static ModelConfigDetailVo toDetailVo(ModelConfigPojo pojo) {
        if (pojo == null) {
            return null;
        }
        ModelConfigVo base = toVo(pojo);
        ModelConfigDetailVo detail = new ModelConfigDetailVo();
        detail.setId(base.getId());
        detail.setName(base.getName());
        detail.setProvider(base.getProvider());
        detail.setProviderName(base.getProviderName());
        detail.setModelType(base.getModelType());
        detail.setModelTypeName(base.getModelTypeName());
        detail.setModelname(base.getModelname());
        detail.setUrl(base.getUrl());
        detail.setHasApiKey(base.getHasApiKey());
        detail.setCreateType(base.getCreateType());
        detail.setCreateUserId(base.getCreateUserId());
        detail.setCreateTime(base.getCreateTime());
        detail.setModifyTime(base.getModifyTime());
        detail.setApiKey(pojo.getApiKey());
        return detail;
    }

    public static LlmBasicConfig toBasicConfig(ModelConfigPojo pojo) {
        return LlmBasicConfig.builder()
                .provider(pojo.getProvider())
                .url(pojo.getUrl())
                .modelName(pojo.getModelname())
                .apiKey(pojo.getApiKey())
                .build();
    }

    private static String providerName(Integer provider) {
        LlmProviderEnum providerEnum = LlmProviderEnum.of(provider);
        return providerEnum == null ? null : providerEnum.getDesc();
    }

    private static String modelTypeName(Integer modelType) {
        LlmModelTypeEnum modelTypeEnum = LlmModelTypeEnum.of(modelType);
        return modelTypeEnum == null ? null : modelTypeEnum.getDesc();
    }
}
