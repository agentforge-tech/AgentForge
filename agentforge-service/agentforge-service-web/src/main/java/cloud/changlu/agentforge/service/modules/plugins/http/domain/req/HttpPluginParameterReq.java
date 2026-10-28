package cloud.changlu.agentforge.service.modules.plugins.http.domain.req;

public class HttpPluginParameterReq {
    private Long id;
    private String methodParamName;
    private String methodParamDescription;
    private String mappedName;
    private Integer useTypeValue;
    private Integer dataTypeValue;
    private String defaultValue;
    private Integer required;
    private Integer sortOrder;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
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
}
