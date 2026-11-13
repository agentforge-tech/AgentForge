package cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo;

import java.util.Date;

public class HttpPluginMethodPojo {
    private Long id;
    private Long pluginId;
    private String methodName;
    private String displayName;
    private String methodDescription;
    private Integer httpMethodType;
    private String uri;
    private Integer status;
    private Integer sortOrder;
    private Integer isDeleted;
    private Date createTime;
    private Date modifyTime;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long v) {
        pluginId = v;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String v) {
        methodName = v;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String v) {
        displayName = v;
    }

    public String getMethodDescription() {
        return methodDescription;
    }

    public void setMethodDescription(String v) {
        methodDescription = v;
    }

    public Integer getHttpMethodType() {
        return httpMethodType;
    }

    public void setHttpMethodType(Integer v) {
        httpMethodType = v;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String v) {
        uri = v;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer v) {
        status = v;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer v) {
        sortOrder = v;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer v) {
        isDeleted = v;
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
}
