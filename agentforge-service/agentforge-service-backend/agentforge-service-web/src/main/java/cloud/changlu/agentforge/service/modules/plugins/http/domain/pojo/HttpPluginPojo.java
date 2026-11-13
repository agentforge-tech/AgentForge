package cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo;

import java.util.Date;

public class HttpPluginPojo {
    private Long id;
    private String name;
    private String alias;
    private String baseUrl;
    private String staticHeaders;
    private String description;
    private String icon;
    private Integer status;
    private Integer isPublic;
    private Long createUserId;
    private Integer isDeleted;
    private Date createTime;
    private Date modifyTime;

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

    public String getStaticHeaders() {
        return staticHeaders;
    }

    public void setStaticHeaders(String v) {
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

    public Integer getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Integer v) {
        isPublic = v;
    }

    public Long getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(Long v) {
        createUserId = v;
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
