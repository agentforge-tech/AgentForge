package cloud.changlu.agentforge.service.modules.plugins.http.component.agent;

import cloud.changlu.agentforge.agent.tool.http.HttpToolFactory;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.service.modules.agent.domain.config.HttpToolConfig;
import cloud.changlu.agentforge.service.modules.plugins.http.dao.HttpPluginMapper;
import cloud.changlu.agentforge.service.modules.plugins.http.dao.HttpPluginMethodMapper;
import cloud.changlu.agentforge.service.modules.plugins.http.dao.HttpPluginParameterMapper;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginMethodPojo;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginParameterPojo;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginPojo;
import cloud.changlu.agentforge.service.modules.plugins.http.support.HttpPluginConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class HttpAgentToolLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAgentToolLoader.class);
    private final HttpPluginMapper pluginMapper;
    private final HttpPluginMethodMapper methodMapper;
    private final HttpPluginParameterMapper parameterMapper;
    private final HttpPluginConverter converter;

    public HttpAgentToolLoader(
            HttpPluginMapper pluginMapper,
            HttpPluginMethodMapper methodMapper,
            HttpPluginParameterMapper parameterMapper,
            HttpPluginConverter converter) {
        this.pluginMapper = pluginMapper;
        this.methodMapper = methodMapper;
        this.parameterMapper = parameterMapper;
        this.converter = converter;
    }

    public Map<ToolSpecification, ToolExecutor> load(List<HttpToolConfig> configs) {
        return loadWithThought(configs, false);
    }

    /**
     * 加载 HTTP 插件工具，可选择向工具 Schema 注入思考参数。
     *
     * @param configs Agent 配置中的 HTTP 插件选择关系
     * @param withThought 是否追加必填的思考参数
     * @return 工具表，可直接注册到 {@code ToolService}
     */
    public Map<ToolSpecification, ToolExecutor> loadWithThought(
            List<HttpToolConfig> configs, boolean withThought) {
        if (configs == null || configs.isEmpty()) return Collections.emptyMap();
        Map<ToolSpecification, ToolExecutor> result =
                new LinkedHashMap<ToolSpecification, ToolExecutor>();
        Set<String> names = new HashSet<String>();
        for (HttpToolConfig config : configs) {
            try {
                Map<ToolSpecification, ToolExecutor> tools = loadOne(config, withThought);
                for (Map.Entry<ToolSpecification, ToolExecutor> entry : tools.entrySet()) {
                    if (!names.add(entry.getKey().name())) {
                        LOGGER.warn(
                                "[HTTP-Agent] duplicate tool skipped: {}", entry.getKey().name());
                        continue;
                    }
                    result.put(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException e) {
                LOGGER.warn(
                        "[HTTP-Agent] plugin load failed, pluginId={}, error={}",
                        config == null ? null : config.getPluginId(),
                        e.getMessage(),
                        e);
            }
        }
        return result;
    }

    private Map<ToolSpecification, ToolExecutor> loadOne(
            HttpToolConfig config, boolean withThought) {
        if (config == null || config.getPluginId() == null) return Collections.emptyMap();
        HttpPluginPojo plugin = pluginMapper.selectById(config.getPluginId());
        if (plugin == null || plugin.getStatus() == null || plugin.getStatus() != 1)
            return Collections.emptyMap();
        List<HttpPluginMethodPojo> methods = resolveMethods(config);
        if (methods.isEmpty()) return Collections.emptyMap();
        List<Long> ids = new ArrayList<Long>();
        for (HttpPluginMethodPojo m : methods) ids.add(m.getId());
        Map<Long, List<HttpPluginParameterPojo>> grouped =
                group(parameterMapper.selectByMethodIds(ids));
        HttpPlugin httpPlugin = converter.convert(plugin, methods, grouped, null);
        return HttpToolFactory.buildHttpToolsWithThought(httpPlugin, withThought);
    }

    private List<HttpPluginMethodPojo> resolveMethods(HttpToolConfig config) {
        Long[] selected = config.getPluginMethodIds();
        List<HttpPluginMethodPojo> source;
        if (selected == null || selected.length == 0)
            source = methodMapper.selectByPluginId(config.getPluginId(), true);
        else {
            List<Long> ids = new ArrayList<Long>();
            for (Long id : selected) if (id != null) ids.add(id);
            if (ids.isEmpty()) return Collections.emptyList();
            source = methodMapper.selectByIds(ids);
        }
        List<HttpPluginMethodPojo> result = new ArrayList<HttpPluginMethodPojo>();
        for (HttpPluginMethodPojo m : source)
            if (config.getPluginId().equals(m.getPluginId())
                    && m.getStatus() != null
                    && m.getStatus() == 1) result.add(m);
        return result;
    }

    private Map<Long, List<HttpPluginParameterPojo>> group(List<HttpPluginParameterPojo> params) {
        Map<Long, List<HttpPluginParameterPojo>> result =
                new LinkedHashMap<Long, List<HttpPluginParameterPojo>>();
        for (HttpPluginParameterPojo p : params) {
            List<HttpPluginParameterPojo> list = result.get(p.getMethodId());
            if (list == null) {
                list = new ArrayList<HttpPluginParameterPojo>();
                result.put(p.getMethodId(), list);
            }
            list.add(p);
        }
        return result;
    }
}
