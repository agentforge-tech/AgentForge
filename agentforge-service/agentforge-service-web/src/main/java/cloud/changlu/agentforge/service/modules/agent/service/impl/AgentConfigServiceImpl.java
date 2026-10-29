package cloud.changlu.agentforge.service.modules.agent.service.impl;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.common.util.AidGeneratorUtil;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigMapper;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigPublishedMapper;
import cloud.changlu.agentforge.service.modules.agent.dao.AgentConfigVersionMapper;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigPublishedPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.AgentConfigVersionPojo;
import cloud.changlu.agentforge.service.modules.agent.domain.config.AgentModelConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.ChatMemoryConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.PluginConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.config.StandardAgentConfig;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigQueryReq;
import cloud.changlu.agentforge.service.modules.agent.domain.req.AgentConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVersionVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.AgentConfigVo;
import cloud.changlu.agentforge.service.modules.agent.domain.vo.PublishedAgentBasicVo;
import cloud.changlu.agentforge.service.modules.agent.runtime.base.IAgentManager;
import cloud.changlu.agentforge.service.modules.agent.runtime.factory.AgentManagerFactory;
import cloud.changlu.agentforge.service.modules.agent.service.IAgentConfigService;
import cloud.changlu.agentforge.service.modules.model.dao.ModelConfigMapper;
import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/** Agent 配置、历史版本与发布快照服务实现。 */
@Service
public class AgentConfigServiceImpl implements IAgentConfigService {

    private static final int STANDARD_AGENT_TYPE = 1;
    private static final long DEFAULT_USER_ID = 1L;
    private static final int VERSION_REMARK_MAX_LENGTH = 1024;

    private final AgentConfigMapper agentConfigMapper;
    private final AgentConfigVersionMapper agentConfigVersionMapper;
    private final AgentConfigPublishedMapper agentConfigPublishedMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final ObjectMapper objectMapper;
    private final AgentManagerFactory agentManagerFactory;

    public AgentConfigServiceImpl(
            AgentConfigMapper agentConfigMapper,
            AgentConfigVersionMapper agentConfigVersionMapper,
            AgentConfigPublishedMapper agentConfigPublishedMapper,
            ModelConfigMapper modelConfigMapper,
            ObjectMapper objectMapper,
            AgentManagerFactory agentManagerFactory) {
        this.agentConfigMapper = agentConfigMapper;
        this.agentConfigVersionMapper = agentConfigVersionMapper;
        this.agentConfigPublishedMapper = agentConfigPublishedMapper;
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
        return toVo(requireAgent(id));
    }

    @Override
    public PublishedAgentBasicVo getPublishedBasicByAid(String aid) {
        AgentConfigPublishedPojo pojo = agentConfigPublishedMapper.selectByAid(requireAid(aid));
        if (pojo == null) {
            throw new IllegalArgumentException("Agent 不存在、已删除或尚未发布");
        }
        PublishedAgentBasicVo vo = new PublishedAgentBasicVo();
        vo.setAid(pojo.getAid());
        vo.setName(pojo.getName());
        vo.setAvatar(pojo.getAvatar());
        vo.setDescription(pojo.getDescription());
        vo.setExperConfig(pojo.getExperConfig());
        vo.setLastPublishTime(pojo.getLastPublishTime());
        return vo;
    }

    @Override
    public List<AgentConfigVersionVo> listVersions(Long id) {
        AgentConfigPojo pojo = requireAgent(id);
        return agentConfigVersionMapper.selectByAid(pojo.getAid()).stream()
                .map(this::toVersionVo)
                .collect(Collectors.toList());
    }

    @Override
    public AgentConfigVersionVo getVersion(Long id, Long versionNo) {
        AgentConfigPojo pojo = requireAgent(id);
        return toVersionVo(requireVersion(pojo.getAid(), versionNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentConfigVo upsert(AgentConfigUpsertReq req) {
        validateUpsertRequest(req);
        Date now = new Date();
        AgentConfigPojo pojo;
        if (req.getId() == null) {
            pojo = buildNewAgent(req, now);
            agentConfigMapper.insert(pojo);
        } else {
            pojo = requireAgentForUpdate(req.getId());
            applyUpsertRequest(pojo, req, now);
            agentConfigMapper.update(pojo);
        }
        AgentConfigPojo saved = requireAgent(pojo.getId());
        AgentConfigVersionPojo submittedVersion = null;
        if (Boolean.TRUE.equals(req.getSubmitVersion())) {
            submittedVersion = createVersionSnapshot(saved, now, req.getVersionRemark());
        }
        evictAfterCommit(saved, true);
        AgentConfigVo result = toVo(saved);
        if (submittedVersion != null) {
            result.setSubmittedVersionNo(submittedVersion.getVersionNo());
        }
        return result;
    }

    @Override
    public AgentConfigVersionVo switchVersion(Long id, Long versionNo) {
        AgentConfigPojo current = requireAgent(id);
        return toVersionVo(requireVersion(current.getAid(), versionNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentConfigVersionVo updateVersionRemark(Long id, Long versionNo, String versionRemark) {
        AgentConfigPojo agent = requireAgent(id);
        AgentConfigVersionPojo version = requireVersion(agent.getAid(), versionNo);
        agentConfigVersionMapper.updateRemark(
                version.getId(), normalizeVersionRemark(versionRemark), new Date());
        return toVersionVo(requireVersion(agent.getAid(), versionNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AgentConfigPojo pojo = requireAgentForUpdate(id);
        agentConfigPublishedMapper.deleteByAid(pojo.getAid());
        agentConfigMapper.deleteById(id);
        evictAllAfterCommit(pojo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentConfigVo updatePublishStatus(Long id, boolean published) {
        AgentConfigPojo pojo = requireAgentForUpdate(id);
        Date now = new Date();
        agentConfigMapper.updatePublishStatus(id, published ? 1 : 0, now);
        agentConfigPublishedMapper.deleteByAid(pojo.getAid());
        if (published) {
            agentConfigPublishedMapper.insert(toPublishedSnapshot(pojo, now));
        }
        evictAfterCommit(pojo, false);
        return toVo(requireAgent(id));
    }

    private void validateUpsertRequest(AgentConfigUpsertReq req) {
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
    }

    private AgentConfigPojo buildNewAgent(AgentConfigUpsertReq req, Date now) {
        int agentType = req.getType() == null ? STANDARD_AGENT_TYPE : req.getType();
        if (agentType != STANDARD_AGENT_TYPE) {
            throw new IllegalArgumentException("不支持的 Agent 类别: " + agentType);
        }
        AgentConfigPojo pojo = new AgentConfigPojo();
        pojo.setType(agentType);
        pojo.setAid(AidGeneratorUtil.getInstance().nextId());
        pojo.setPublish(0);
        pojo.setIsPublic(0);
        pojo.setAvatar(normalizeAvatar(req.getAvatar()));
        pojo.setDescription(normalizeDescription(req.getDescription()));
        pojo.setIsDeleted(0);
        pojo.setCreateUserId(DEFAULT_USER_ID);
        pojo.setCreateTime(now);
        pojo.setModifyTime(now);
        pojo.setName(req.getName().trim());
        pojo.setAgentConfig(
                buildAgentConfig(
                        req.getModelId(),
                        req.getSystemPrompt(),
                        req.getChatMemoryType(),
                        req.getChatMemoryMaxMessages(),
                        req.getPluginConfig()));
        return pojo;
    }

    private void applyUpsertRequest(
            AgentConfigPojo pojo, AgentConfigUpsertReq req, Date modifyTime) {
        pojo.setName(req.getName().trim());
        pojo.setDescription(normalizeDescription(req.getDescription()));
        if (req.getAvatar() != null) {
            pojo.setAvatar(normalizeAvatar(req.getAvatar()));
        }
        StandardAgentConfig oldConfig = parseAgentConfig(pojo.getAgentConfig());
        String prompt =
                req.getSystemPrompt() == null ? oldConfig.getSystemPrompt() : req.getSystemPrompt();
        ChatMemoryConfig oldMemoryConfig = oldConfig.getChatMemoryConfig();
        Integer memoryType =
                req.getChatMemoryType() == null
                        ? resolveMemoryType(oldMemoryConfig)
                        : req.getChatMemoryType();
        Integer maxMessages =
                req.getChatMemoryMaxMessages() == null
                        ? resolveMaxMessages(oldMemoryConfig)
                        : req.getChatMemoryMaxMessages();
        pojo.setAgentConfig(
                buildAgentConfig(
                        req.getModelId(),
                        prompt,
                        memoryType,
                        maxMessages,
                        req.getPluginConfig() == null
                                ? oldConfig.getPluginConfig()
                                : req.getPluginConfig()));
        pojo.setModifyTime(modifyTime);
    }

    private AgentConfigVersionPojo createVersionSnapshot(
            AgentConfigPojo config, Date now, String versionRemark) {
        Long maxVersionNo = agentConfigVersionMapper.selectMaxVersionNo(config.getAid());
        AgentConfigVersionPojo version = new AgentConfigVersionPojo();
        version.setVersionNo(maxVersionNo == null ? 1L : maxVersionNo + 1L);
        version.setName(config.getName());
        version.setType(config.getType());
        version.setAgentConfig(config.getAgentConfig());
        version.setExperConfig(config.getExperConfig());
        version.setAid(config.getAid());
        version.setAvatar(config.getAvatar());
        version.setDescription(config.getDescription());
        version.setVersionRemark(normalizeVersionRemark(versionRemark));
        version.setIsDeleted(0);
        version.setCreateUserId(DEFAULT_USER_ID);
        version.setCreateTime(now);
        version.setModifyTime(now);
        agentConfigVersionMapper.insert(version);
        return version;
    }

    private AgentConfigPublishedPojo toPublishedSnapshot(AgentConfigPojo config, Date now) {
        AgentConfigPublishedPojo published = new AgentConfigPublishedPojo();
        published.setName(config.getName());
        published.setType(config.getType());
        published.setAgentConfig(config.getAgentConfig());
        published.setExperConfig(config.getExperConfig());
        published.setLastPublishTime(now);
        published.setAid(config.getAid());
        published.setAvatar(config.getAvatar());
        published.setDescription(config.getDescription());
        published.setIsDeleted(0);
        published.setCreateUserId(config.getCreateUserId());
        published.setCreateTime(now);
        published.setModifyTime(now);
        return published;
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

    private AgentConfigPojo requireAgentForUpdate(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        AgentConfigPojo pojo = agentConfigMapper.selectByIdForUpdate(id);
        if (pojo == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除, id: " + id);
        }
        return pojo;
    }

    private AgentConfigVersionPojo requireVersion(String aid, Long versionNo) {
        if (versionNo == null || versionNo < 1) {
            throw new IllegalArgumentException("versionNo 必须大于 0");
        }
        AgentConfigVersionPojo version =
                agentConfigVersionMapper.selectByAidAndVersionNo(aid, versionNo);
        if (version == null) {
            throw new IllegalArgumentException("Agent 历史版本不存在: V" + versionNo);
        }
        return version;
    }

    private String requireAid(String aid) {
        if (aid == null || aid.trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        return aid.trim();
    }

    private void evictAfterCommit(AgentConfigPojo pojo, boolean debug) {
        afterCommit(
                () -> {
                    IAgentManager<Object, Object> agentManager =
                            agentManagerFactory.getAgentManagerByType(pojo.getType());
                    agentManager.removeAgent(pojo.getAid(), debug);
                });
    }

    private void evictAllAfterCommit(AgentConfigPojo pojo) {
        afterCommit(
                () -> {
                    IAgentManager<Object, Object> agentManager =
                            agentManagerFactory.getAgentManagerByType(pojo.getType());
                    agentManager.removeAgent(pojo.getAid());
                });
    }

    private void afterCommit(Runnable callback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        callback.run();
                    }
                });
    }

    private String normalizeVersionRemark(String versionRemark) {
        if (versionRemark == null) {
            return "";
        }
        String trimmed = versionRemark.trim();
        if (trimmed.length() > VERSION_REMARK_MAX_LENGTH) {
            throw new IllegalArgumentException("版本备注不能超过 " + VERSION_REMARK_MAX_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private String normalizeAvatar(String avatar) {
        String normalized = avatar == null ? "" : avatar.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("Agent 头像地址不能超过 255 个字符");
        }
        return normalized;
    }

    private String buildAgentConfig(
            Long modelId,
            String prompt,
            Integer memoryType,
            Integer maxMessages,
            PluginConfig pluginConfig) {
        AgentModelConfig chatModel = new AgentModelConfig();
        chatModel.setLlmModelId(modelId);
        StandardAgentConfig config = new StandardAgentConfig();
        config.setSystemPrompt(
                prompt == null || prompt.trim().isEmpty()
                        ? "你是一名专业的 AI 助手，请准确、清晰地回答用户问题。"
                        : prompt);
        config.setChatModel(chatModel);
        ChatMemoryConfig memoryConfig = new ChatMemoryConfig();
        if (memoryType != null) {
            memoryConfig.setType(memoryType);
        }
        if (maxMessages != null && maxMessages > 0) {
            memoryConfig.setMemoryConfig("{\"maxMessage\":" + maxMessages + "}");
        }
        config.setChatMemoryConfig(memoryConfig);
        config.setPluginConfig(pluginConfig);
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
        vo.setLastPublishTime(pojo.getLastPublishTime());
        vo.setIsPublic(pojo.getIsPublic());
        vo.setAvatar(pojo.getAvatar());
        vo.setDescription(pojo.getDescription());
        vo.setCreateTime(pojo.getCreateTime());
        vo.setModifyTime(pojo.getModifyTime());
        StandardAgentConfig config = parseAgentConfig(pojo.getAgentConfig());
        vo.setSystemPrompt(config.getSystemPrompt());
        vo.setChatMemoryType(resolveMemoryType(config.getChatMemoryConfig()));
        vo.setChatMemoryMaxMessages(resolveMaxMessages(config.getChatMemoryConfig()));
        vo.setPluginConfig(config.getPluginConfig());
        Long modelId = readModelId(pojo.getAgentConfig());
        vo.setModelId(modelId);
        if (modelId != null) {
            ModelConfigPojo model = modelConfigMapper.selectById(modelId);
            vo.setModelName(model == null ? "模型已删除" : model.getName());
        }
        return vo;
    }

    private AgentConfigVersionVo toVersionVo(AgentConfigVersionPojo pojo) {
        AgentConfigVersionVo vo = new AgentConfigVersionVo();
        vo.setId(pojo.getId());
        vo.setVersionNo(pojo.getVersionNo());
        vo.setAid(pojo.getAid());
        vo.setName(pojo.getName());
        vo.setType(pojo.getType());
        vo.setAgentConfig(pojo.getAgentConfig());
        vo.setExperConfig(pojo.getExperConfig());
        vo.setAvatar(pojo.getAvatar());
        vo.setDescription(pojo.getDescription());
        vo.setVersionRemark(pojo.getVersionRemark());
        vo.setCreateUserId(pojo.getCreateUserId());
        vo.setCreateTime(pojo.getCreateTime());
        vo.setModifyTime(pojo.getModifyTime());
        return vo;
    }

    private int resolveMemoryType(ChatMemoryConfig config) {
        return config == null || config.getType() == null ? 1 : config.getType();
    }

    private int resolveMaxMessages(ChatMemoryConfig config) {
        if (config == null
                || config.getMemoryConfig() == null
                || config.getMemoryConfig().trim().isEmpty()) {
            return 1000;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode maxMessage =
                    objectMapper.readTree(config.getMemoryConfig()).get("maxMessage");
            return maxMessage != null && maxMessage.isInt() && maxMessage.asInt() > 0
                    ? maxMessage.asInt()
                    : 1000;
        } catch (Exception ignored) {
            return 1000;
        }
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
