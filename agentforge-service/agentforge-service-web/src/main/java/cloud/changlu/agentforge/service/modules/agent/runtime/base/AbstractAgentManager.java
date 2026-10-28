package cloud.changlu.agentforge.service.modules.agent.runtime.base;

import cloud.changlu.agentforge.service.modules.agent.runtime.context.AgentInitContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 封装 Agent 创建、配置解析与正式/调试实例缓存的通用流程。 */
public abstract class AbstractAgentManager<T, F> implements IAgentManager<T, F> {

    public static final String TEMP_AID_TEMPLATE = "temp_%s";

    private final Map<String, T> agents = new ConcurrentHashMap<>();
    private final Map<String, F> agentConfigs = new ConcurrentHashMap<>();

    @Override
    public final T createAndStoreAgent(String aid, String agentConfig, boolean isDebug) {
        return createAndStoreAgent(defaultContext(aid, agentConfig, isDebug));
    }

    @Override
    public final T createAndStoreAgent(AgentInitContext context) {
        T agent = createAgent(context);
        F parsedConfig = parseAgentConf(context.getAgentConfig());
        String agentKey = buildAgentKey(context);
        agents.put(agentKey, agent);
        agentConfigs.put(agentKey, parsedConfig);
        return agent;
    }

    @Override
    public final T getAgent(String aid, boolean isDebug) {
        return getAgent(defaultContext(aid, null, isDebug));
    }

    @Override
    public final T getAgent(AgentInitContext context) {
        return agents.get(buildAgentKey(context));
    }

    @Override
    public final T getAgentAndInitAgent(String aid, String agentConfig, boolean isDebug) {
        return getAgentAndInitAgent(defaultContext(aid, agentConfig, isDebug));
    }

    @Override
    public final T getAgentAndInitAgent(AgentInitContext context) {
        String agentKey = buildAgentKey(context);
        T agent = getAgent(context);
        if (agent == null) {
            synchronized (agentKey.intern()) {
                agent = getAgent(context);
                if (agent == null) agent = createAndStoreAgent(context);
            }
        }
        return agent;
    }

    @Override
    public final void removeAgent(String aid, boolean isDebug) {
        if (aid != null) {
            String aidPrefix = buildAidPrefix(aid, isDebug);
            agents.keySet().removeIf(key -> key.startsWith(aidPrefix));
            agentConfigs.keySet().removeIf(key -> key.startsWith(aidPrefix));
        }
    }

    @Override
    public final void removeAgent(String aid) {
        if (aid != null) {
            removeAgent(aid, false);
            removeAgent(aid, true);
        }
    }

    @Override
    public final void clearAgents() {
        agents.clear();
        agentConfigs.clear();
    }

    protected abstract F parseAgentConf(String agentConfig);

    protected abstract T createAgent(AgentInitContext context);

    protected String buildAgentKey(AgentInitContext context) {
        return buildAidPrefix(context.getAid(), context.isDebug())
                + context.getNormalizedChannelId();
    }

    private AgentInitContext defaultContext(String aid, String config, boolean debug) {
        return AgentInitContext.builder()
                .aid(aid)
                .agentConfig(config)
                .debug(debug)
                .channelId(AgentInitContext.WEBSITE_CHANNEL_ID)
                .build();
    }

    private String buildAidPrefix(String aid, boolean isDebug) {
        return (isDebug ? String.format(TEMP_AID_TEMPLATE, aid) : aid) + "::";
    }
}
