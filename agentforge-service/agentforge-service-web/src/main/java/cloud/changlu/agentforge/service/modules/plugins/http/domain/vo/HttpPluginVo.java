package cloud.changlu.agentforge.service.modules.plugins.http.domain.vo;

import java.util.Date;
import java.util.List;
import java.util.Map;

public class HttpPluginVo {
    private Long id;
    private String name;
    private String alias;
    private String baseUrl;
    private Map<String, String> staticHeaders;
    private List<String> sensitiveHeaderKeys;
    private String description;
    private String icon;
    private Integer status;
    private Integer methodCount;
    private Date createTime;
    private Date modifyTime;
    private List<HttpPluginMethodVo> methods;

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

    public List<String> getSensitiveHeaderKeys() {
        return sensitiveHeaderKeys;
    }

    public void setSensitiveHeaderKeys(List<String> v) {
        sensitiveHeaderKeys = v;
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

    public Integer getMethodCount() {
        return methodCount;
    }

    public void setMethodCount(Integer v) {
        methodCount = v;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date v) {
        createTime = v;
    }

    public Date getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(Date v) {
        modifyTime = v;
    }

    public List<HttpPluginMethodVo> getMethods() {
        return methods;
    }

    public void setMethods(List<HttpPluginMethodVo> v) {
        methods = v;
    }
}
