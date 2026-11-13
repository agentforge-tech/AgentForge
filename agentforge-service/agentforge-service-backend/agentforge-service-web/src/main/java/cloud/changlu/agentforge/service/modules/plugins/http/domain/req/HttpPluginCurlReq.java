package cloud.changlu.agentforge.service.modules.plugins.http.domain.req;

public class HttpPluginCurlReq {
    private Long pluginId;
    private String curlCommand;
    private String pluginName;
    private String pluginDescription;

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long v) {
        pluginId = v;
    }

    public String getCurlCommand() {
        return curlCommand;
    }

    public void setCurlCommand(String v) {
        curlCommand = v;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String v) {
        pluginName = v;
    }

    public String getPluginDescription() {
        return pluginDescription;
    }

    public void setPluginDescription(String v) {
        pluginDescription = v;
    }
}
