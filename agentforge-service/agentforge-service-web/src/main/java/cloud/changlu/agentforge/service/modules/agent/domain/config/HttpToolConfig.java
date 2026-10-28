package cloud.changlu.agentforge.service.modules.agent.domain.config;

import java.util.Arrays;

/** Agent 中绑定的 HTTP 插件及方法。 */
public class HttpToolConfig {

    private Long pluginId;
    private Long[] pluginMethodIds;

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long pluginId) {
        this.pluginId = pluginId;
    }

    public Long[] getPluginMethodIds() {
        return pluginMethodIds;
    }

    public void setPluginMethodIds(Long[] pluginMethodIds) {
        this.pluginMethodIds = pluginMethodIds;
    }

    @Override
    public String toString() {
        return "HttpToolConfig{pluginId="
                + pluginId
                + ", pluginMethodIds="
                + Arrays.toString(pluginMethodIds)
                + '}';
    }
}
