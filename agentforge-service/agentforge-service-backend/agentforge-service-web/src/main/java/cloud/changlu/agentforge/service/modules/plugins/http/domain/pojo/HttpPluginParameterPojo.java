package cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo;

import java.util.Date;

public class HttpPluginParameterPojo {
    private Long id;
    private Long methodId;
    private String methodParamName;
    private String methodParamDescription;
    private String mappedName;
    private Integer useTypeValue;
    private Integer dataTypeValue;
    private String defaultValue;
    private Integer required;
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

    public Long getMethodId() {
        return methodId;
    }

    public void setMethodId(Long v) {
        methodId = v;
    }

    public String getMethodParamName() {
        return methodParamName;
    }

    public void setMethodParamName(String v) {
        methodParamName = v;
    }

    public String getMethodParamDescription() {
        return methodParamDescription;
    }

    public void setMethodParamDescription(String v) {
        methodParamDescription = v;
    }

    public String getMappedName() {
        return mappedName;
    }

    public void setMappedName(String v) {
        mappedName = v;
    }

    public Integer getUseTypeValue() {
        return useTypeValue;
    }

    public void setUseTypeValue(Integer v) {
        useTypeValue = v;
    }

    public Integer getDataTypeValue() {
        return dataTypeValue;
    }

    public void setDataTypeValue(Integer v) {
        dataTypeValue = v;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String v) {
        defaultValue = v;
    }

    public Integer getRequired() {
        return required;
    }

    public void setRequired(Integer v) {
        required = v;
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
