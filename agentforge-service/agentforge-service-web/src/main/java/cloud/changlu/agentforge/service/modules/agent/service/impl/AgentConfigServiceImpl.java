package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.ChatMemoryConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVo;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.IAgentConfigService;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Agent 配置服务实现。 */
@Service
public class AgentConfigServiceImpl implements IAgentConfigService {

    private static final int REACT_AGENT = 1;
    private static final long DEFAULT_USER_ID = 1L;

    private final AgentConfigMapper agentConfigMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final ObjectMapper objectMapper;
    private final AgentManagerFactory agentManagerFactory;

    public AgentConfigServiceImpl(
            AgentConfigMapper agentConfigMapper,
            ModelConfigMapper modelConfigMapper,
            ObjectMapper objectMapper,
            AgentManagerFactory agentManagerFactory) {
        this.agentConfigMapper = agentConfigMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.objectMapper = objectMapper;
        this.agentManagerFactory = agentManagerFactory;
    }

    @Override
    public PageResult<List<AgentConfigVo>> page(AgentConfigQueryReq req) {
        AgentConfigQueryReq query = req == null ? new AgentConfigQueryReq() : req;
        int currentPage = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int pageSize =
                query.getPageSize() == null || query.getPageSize() < 1
                        ? 12
                        : Math.min(query.getPageSize(), 100);
        query.setPage(currentPage);
        query.setPageSize(pageSize);
        long totalCount = agentConfigMapper.selectCount(query);
        List<AgentConfigVo> data =
                agentConfigMapper.selectPage(query).stream()
                        .map(this::toVo)
                        .collect(Collectors.toList());
        return new PageResult<>(currentPage, pageSize, totalCount, data);
    }

    @Override
    public AgentConfigVo getById(Long id) {
        AgentConfigPojo pojo = requireAgent(id);
        return toVo(pojo);
    }

    @Override
    public AgentConfigVo upsert(AgentConfigUpsertReq req) {
        if (req == null || req.getName() == null || req.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Agent 名称不能为空");
        }
        if (req.getModelId() == null) {
            throw new IllegalArgumentException("请选择模型");
        }
        ModelConfigPojo model = modelConfigMapper.selectById(req.getModelId());
        if (model == null) {
            throw new IllegalArgumentException("所选模型不存在或已被删除");
        }

        Date now = new Date();
        AgentConfigPojo pojo;
        if (req.getId() == null) {
            pojo = new AgentConfigPojo();
            pojo.setType(REACT_AGENT);
            pojo.setAid(UUID.randomUUID().toString().replace("-", ""));
            pojo.setPublish(0);
            pojo.setIsPublic(0);
            pojo.setAvatar("");
            pojo.setDescription("");
            pojo.setIsDeleted(0);
            pojo.setCreateUserId(DEFAULT_USER_ID);
            pojo.setCreateTime(now);
            pojo.setModifyTime(now);
            pojo.setName(req.getName().trim());
            pojo.setAgentConfig(
                    buildAgentConfig(
                            req.getModelId(),
                            req.getSystemPrompt(),
                            req.getChatMemoryMaxMessages()));
            agentConfigMapper.insert(pojo);
        } else {
            pojo = requireAgent(req.getId());
            pojo.setName(req.getName().trim());
            StandardAgentConfig oldConfig = parseAgentConfig(pojo.getAgentConfig());
            String prompt =
                    req.getSystemPrompt() == null
                            ? oldConfig.getSystemPrompt()
                            : req.getSystemPrompt();
            Integer maxMessages =
                    req.getChatMemoryMaxMessages() == null
                            ? oldConfig.getChatMemoryConfig().getChatMemoryMaxMessages()
                            : req.getChatMemoryMaxMessages();
            pojo.setAgentConfig(buildAgentConfig(req.getModelId(), prompt, maxMessages));
            pojo.setModifyTime(now);
            agentConfigMapper.update(pojo);
        }
        AgentConfigPojo saved = agentConfigMapper.selectById(pojo.getId());
        IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByType(saved.getType());
        agentManager.createAndStoreAgent(saved.getAid(), saved.getAgentConfig(), false);
        return toVo(saved);
    }

    @Override
    public void delete(Long id) {
        AgentConfigPojo pojo = requireAgent(id);
        agentConfigMapper.deleteById(id);
        IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByType(pojo.getType());
        agentManager.removeAgent(pojo.getAid());
    }

    @Override
    public AgentConfigVo updatePublishStatus(Long id, boolean published) {
        AgentConfigPojo pojo = requireAgent(id);
        agentConfigMapper.updatePublishStatus(id, published ? 1 : 0, new Date());
        IAgentManager<Object, Object> agentManager =
                agentManagerFactory.getAgentManagerByType(pojo.getType());
        if (published) {
            agentManager.createAndStoreAgent(pojo.getAid(), pojo.getAgentConfig(), false);
        } else {
            agentManager.removeAgent(pojo.getAid(), false);
        }
        return toVo(requireAgent(id));
    }

    private AgentConfigPojo requireAgent(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        AgentConfigPojo pojo = agentConfigMapper.selectById(id);
        if (pojo == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除, id: " + id);
        }
        return pojo;
    }

    private String buildAgentConfig(Long modelId, String prompt, Integer maxMessages) {
        AgentModelConfig chatModel = new AgentModelConfig();
        chatModel.setLlmModelId(modelId);
        StandardAgentConfig config = new StandardAgentConfig();
        config.setSystemPrompt(
                prompt == null || prompt.trim().isEmpty()
                        ? "你是一名专业的 AI 助手，请准确、清晰地回答用户问题。"
                        : prompt);
        config.setChatModel(chatModel);
        ChatMemoryConfig memoryConfig = new ChatMemoryConfig();
        if (maxMessages != null && maxMessages > 0) {
            memoryConfig.setChatMemoryMaxMessages(maxMessages);
        }
        config.setChatMemoryConfig(memoryConfig);
        return StandardAgentConfig.toJson(config, objectMapper);
    }

    private StandardAgentConfig parseAgentConfig(String agentConfig) {
        return StandardAgentConfig.fromJson(agentConfig, objectMapper);
    }

    private AgentConfigVo toVo(AgentConfigPojo pojo) {
        AgentConfigVo vo = new AgentConfigVo();
        vo.setId(pojo.getId());
        vo.setAid(pojo.getAid());
        vo.setName(pojo.getName());
        vo.setType(pojo.getType());
        vo.setPublish(pojo.getPublish());
        vo.setIsPublic(pojo.getIsPublic());
        vo.setAvatar(pojo.getAvatar());
        vo.setDescription(pojo.getDescription());
        vo.setCreateTime(pojo.getCreateTime());
        vo.setModifyTime(pojo.getModifyTime());
        StandardAgentConfig config = parseAgentConfig(pojo.getAgentConfig());
        vo.setSystemPrompt(config.getSystemPrompt());
        vo.setChatMemoryMaxMessages(
                config.getChatMemoryConfig() == null
                        ? 100
                        : config.getChatMemoryConfig().getChatMemoryMaxMessages());
        Long modelId = readModelId(pojo.getAgentConfig());
        vo.setModelId(modelId);
        if (modelId != null) {
            ModelConfigPojo model = modelConfigMapper.selectById(modelId);
            vo.setModelName(model == null ? "模型已删除" : model.getName());
        }
        return vo;
    }

    private Long readModelId(String agentConfig) {
        if (agentConfig == null || agentConfig.trim().isEmpty()) {
            return null;
        }
        try {
            StandardAgentConfig config = parseAgentConfig(agentConfig);
            return config.getChatModel() == null ? null : config.getChatModel().getLlmModelId();
        } catch (Exception ignored) {
            return null;
        }
    }
}
