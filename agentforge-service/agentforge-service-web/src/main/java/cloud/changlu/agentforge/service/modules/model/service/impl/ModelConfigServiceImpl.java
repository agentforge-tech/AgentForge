package cloud.changlu.agentforge.service.modules.model.service.impl;

import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.request.ToolChoice;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.tool.spec.ToolParameters;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigQueryReq;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigTestReq;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.model.domain.transfer.ModelConfigTransfer;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigDetailVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigOptionVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigOptionsVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigTestVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigVo;
import cloud.changlu.agentforge.service.modules.model.enums.LlmModelTypeEnum;
import cloud.changlu.agentforge.service.modules.model.enums.LlmProviderEnum;
import cloud.changlu.agentforge.service.modules.model.service.IModelConfigService;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 模型配置 Service 实现。
 *
 * @author changlu
 * @since 2026-10-14
 */
@Service
public class ModelConfigServiceImpl implements IModelConfigService {

    private static final long DEFAULT_USER_ID = 1L;
    private static final int CREATE_TYPE_USER = 1;
    private static final int NOT_DELETED = 0;

    private static final String PROBE_TOOL_NAME = "agentforge_function_call_probe";

    private final ModelConfigMapper modelConfigMapper;

    public ModelConfigServiceImpl(ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
    }

    @Override
    public ModelConfigVo upsert(ModelConfigUpsertReq req) {
        if (req == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        ModelConfigPojo pojo = ModelConfigTransfer.toPojo(req);
        validateEnums(pojo);
        Date now = new Date();
        if (pojo.getId() == null) {
            if (pojo.getCreateType() == null) {
                pojo.setCreateType(CREATE_TYPE_USER);
            }
            if (pojo.getCreateUserId() == null) {
                pojo.setCreateUserId(DEFAULT_USER_ID);
            }
            pojo.setIsDeleted(NOT_DELETED);
            pojo.setCreateTime(now);
            pojo.setModifyTime(now);
            modelConfigMapper.insert(pojo);
        } else {
            pojo.setModifyTime(now);
            modelConfigMapper.update(pojo);
        }
        return ModelConfigTransfer.toVo(modelConfigMapper.selectById(pojo.getId()));
    }

    @Override
    public ModelConfigDetailVo getById(Long id) {
        ModelConfigPojo pojo = modelConfigMapper.selectById(id);
        if (pojo == null) {
            throw new IllegalArgumentException("模型不存在或已被删除, id: " + id);
        }
        return ModelConfigTransfer.toDetailVo(pojo);
    }

    @Override
    public List<ModelConfigVo> list(ModelConfigQueryReq req) {
        ModelConfigQueryReq query = req == null ? new ModelConfigQueryReq() : req;
        return modelConfigMapper.selectList(query).stream()
                .map(ModelConfigTransfer::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public PageResult<List<ModelConfigVo>> page(ModelConfigQueryReq req) {
        ModelConfigQueryReq query = req == null ? new ModelConfigQueryReq() : req;
        int currentPage = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int pageSize =
                query.getPageSize() == null || query.getPageSize() < 1
                        ? 12
                        : Math.min(query.getPageSize(), 100);
        query.setPage(currentPage);
        query.setPageSize(pageSize);
        long totalCount = modelConfigMapper.selectCount(query);
        List<ModelConfigVo> data =
                modelConfigMapper.selectPage(query).stream()
                        .map(ModelConfigTransfer::toVo)
                        .collect(Collectors.toList());
        return new PageResult<>(currentPage, pageSize, totalCount, data);
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        modelConfigMapper.deleteById(id);
    }

    @Override
    public ModelConfigOptionsVo options() {
        List<ModelConfigOptionVo> providers = new ArrayList<>();
        for (LlmProviderEnum provider : LlmProviderEnum.values()) {
            providers.add(new ModelConfigOptionVo(provider.getCode(), provider.getDesc(), null));
        }
        List<ModelConfigOptionVo> modelTypes = new ArrayList<>();
        for (LlmModelTypeEnum modelType : LlmModelTypeEnum.values()) {
            modelTypes.add(
                    new ModelConfigOptionVo(
                            modelType.getCode(), modelType.getDesc(), modelType.getName()));
        }
        return new ModelConfigOptionsVo(providers, modelTypes);
    }

    @Override
    public ModelConfigTestVo testConnectivity(ModelConfigTestReq req) {
        ModelConfigPojo pojo = resolveModel(req);
        Integer testType = resolveTestType(req, pojo);
        ModelConfigTestVo vo = new ModelConfigTestVo();
        vo.setModelId(pojo.getId());
        vo.setTestType(testType);
        try {
            if (LlmModelTypeEnum.CHAT.getCode() != testType) {
                return failure(vo, "暂不支持的测试类型: " + testType + "，当前仅支持 llm");
            }
            ChatModel chatModel =
                    LlmFactory.buildChatModel(ModelConfigTransfer.toBasicConfig(pojo));
            chatModel.chat("ping");
            return success(vo, "模型连通性测试成功");
        } catch (Exception e) {
            return failure(vo, "模型连通性测试失败: " + e.getMessage());
        }
    }

    @Override
    public ModelConfigTestVo testFunctionCall(ModelConfigTestReq req) {
        ModelConfigPojo pojo = resolveModel(req);
        ModelConfigTestVo vo = new ModelConfigTestVo();
        vo.setModelId(pojo.getId());
        vo.setTestType(LlmModelTypeEnum.CHAT.getCode());
        try {
            ChatModel chatModel =
                    LlmFactory.buildChatModel(ModelConfigTransfer.toBasicConfig(pojo));
            ToolSpecification probeTool =
                    ToolSpecification.builder()
                            .name(PROBE_TOOL_NAME)
                            .description("Echo the given text back. Always call this tool.")
                            .parameters(
                                    ToolParameters.builder()
                                            .addProperty("text", "string", "the text to echo", true)
                                            .build())
                            .build();
            DefaultChatRequestParameters parameters =
                    DefaultChatRequestParameters.builder()
                            .tools(Collections.singletonList(probeTool))
                            .toolChoice(ToolChoice.REQUIRED)
                            .build();
            ChatResponse response =
                    chatModel.chat(
                            ChatRequest.builder()
                                    .message(
                                            UserMessage.from(
                                                    "Call the "
                                                            + PROBE_TOOL_NAME
                                                            + " tool with text \"ping\"."))
                                    .parameters(parameters)
                                    .build());
            boolean supported =
                    response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests();
            if (supported) {
                return success(vo, "模型支持 Function Call");
            }
            return failure(vo, "模型未返回工具调用，可能不支持 Function Call");
        } catch (Exception e) {
            return failure(vo, "Function Call 测试失败: " + e.getMessage());
        }
    }

    private void validateEnums(ModelConfigPojo pojo) {
        if (pojo.getProvider() != null && LlmProviderEnum.of(pojo.getProvider()) == null) {
            throw new IllegalArgumentException("不支持的模型厂商: " + pojo.getProvider());
        }
        if (pojo.getModelType() != null && LlmModelTypeEnum.of(pojo.getModelType()) == null) {
            throw new IllegalArgumentException("不支持的模型类别: " + pojo.getModelType());
        }
    }

    private ModelConfigPojo resolveModel(ModelConfigTestReq req) {
        if (req == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (req.getModelId() != null) {
            ModelConfigPojo pojo = modelConfigMapper.selectById(req.getModelId());
            if (pojo == null) {
                throw new IllegalArgumentException("模型不存在或已被删除, id: " + req.getModelId());
            }
            // 弹窗测试时优先使用当前表单值；API Key 留空则沿用已保存的密钥。
            if (req.getProvider() != null) {
                pojo.setProvider(req.getProvider());
            }
            if (req.getModelType() != null) {
                pojo.setModelType(req.getModelType());
            }
            if (req.getModelname() != null && !req.getModelname().trim().isEmpty()) {
                pojo.setModelname(req.getModelname());
            }
            if (req.getUrl() != null && !req.getUrl().trim().isEmpty()) {
                pojo.setUrl(req.getUrl());
            }
            if (req.getApiKey() != null && !req.getApiKey().trim().isEmpty()) {
                pojo.setApiKey(req.getApiKey());
            }
            return pojo;
        }
        if (req.getProvider() == null || req.getModelname() == null) {
            throw new IllegalArgumentException("请提供 modelId 或完整的内联模型配置(provider/modelname)");
        }
        ModelConfigPojo pojo = new ModelConfigPojo();
        pojo.setProvider(req.getProvider());
        pojo.setModelType(req.getModelType());
        pojo.setModelname(req.getModelname());
        pojo.setUrl(req.getUrl());
        pojo.setApiKey(req.getApiKey());
        return pojo;
    }

    private Integer resolveTestType(ModelConfigTestReq req, ModelConfigPojo pojo) {
        if (req.getTestType() != null) {
            return req.getTestType();
        }
        if (pojo.getModelType() != null) {
            return pojo.getModelType();
        }
        return LlmModelTypeEnum.CHAT.getCode();
    }

    private ModelConfigTestVo success(ModelConfigTestVo vo, String message) {
        vo.setSuccess(true);
        vo.setMessage(message);
        return vo;
    }

    private ModelConfigTestVo failure(ModelConfigTestVo vo, String message) {
        vo.setSuccess(false);
        vo.setMessage(message);
        return vo;
    }
}
