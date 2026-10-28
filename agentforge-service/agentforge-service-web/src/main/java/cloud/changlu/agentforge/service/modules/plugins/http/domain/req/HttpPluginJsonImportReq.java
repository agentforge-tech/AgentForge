package cloud.changlu.agentforge.service.modules.plugins.http.domain.req;

import java.util.List;
import java.util.Map;

/** AgentForge HTTP 插件原生 JSON 导入、导出结构。 */
public class HttpPluginJsonImportReq {
    private String version;
    private String source;
    private Plugin plugin;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    public static class Plugin {
        private String name;
        private String alias;
        private String icon;
        private String baseUrl;
        private Map<String, String> staticHeaders;
        private String description;
        private Integer status;
        private List<Method> methods;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Map<String, String> getStaticHeaders() {
            return staticHeaders;
        }

        public void setStaticHeaders(Map<String, String> staticHeaders) {
            this.staticHeaders = staticHeaders;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public List<Method> getMethods() {
            return methods;
        }

        public void setMethods(List<Method> methods) {
            this.methods = methods;
        }
    }

    public static class Method {
        private String methodName;
        private String displayName;
        private String methodDescription;
        private Integer httpMethodType;
        private String uri;
        private Integer status;
        private Integer sortOrder;
        private List<HttpPluginParameterReq> parameters;

        public String getMethodName() {
            return methodName;
        }

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getMethodDescription() {
            return methodDescription;
        }

        public void setMethodDescription(String methodDescription) {
            this.methodDescription = methodDescription;
        }

        public Integer getHttpMethodType() {
            return httpMethodType;
        }

        public void setHttpMethodType(Integer httpMethodType) {
            this.httpMethodType = httpMethodType;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public List<HttpPluginParameterReq> getParameters() {
            return parameters;
        }

        public void setParameters(List<HttpPluginParameterReq> parameters) {
            this.parameters = parameters;
        }
    }
}
