package cloud.changlu.agentforge.service.modules.plugins.http.domain.req;

import java.util.Map;

public class HttpPluginSaveReq {
    private Long id;
    private String name;
    private String alias;
    private String baseUrl;
    private Map<String, String> staticHeaders;
    private String description;
    private String icon;
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String v) {
        alias = v;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String v) {
        baseUrl = v;
    }

    public Map<String, String> getStaticHeaders() {
        return staticHeaders;
    }

    public void setStaticHeaders(Map<String, String> v) {
        staticHeaders = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String v) {
        icon = v;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer v) {
        status = v;
    }
}
