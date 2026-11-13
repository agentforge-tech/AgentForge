package cloud.changlu.agentforge.service.modules.plugins.http.support;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginMethodPojo;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginParameterPojo;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginPojo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpPluginConverter {
    private final ObjectMapper objectMapper;

    public HttpPluginConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public HttpPlugin convert(
            HttpPluginPojo plugin,
            List<HttpPluginMethodPojo> methods,
            Map<Long, List<HttpPluginParameterPojo>> grouped,
            String aliasOverride) {
        String prefix = blank(aliasOverride) ? plugin.getAlias() : aliasOverride.trim();
        List<HttpPluginMethod> result = new ArrayList<HttpPluginMethod>();
        for (HttpPluginMethodPojo method : methods) {
            List<HttpToolParameter> params = new ArrayList<HttpToolParameter>();
            List<HttpPluginParameterPojo> source = grouped.get(method.getId());
            if (source != null)
                for (HttpPluginParameterPojo p : source)
                    params.add(
                            HttpToolParameter.builder()
                                    .methodParamName(p.getMethodParamName())
                                    .methodParamDescription(p.getMethodParamDescription())
                                    .mappedName(p.getMappedName())
                                    .useTypeValue(p.getUseTypeValue())
                                    .dataTypeValue(p.getDataTypeValue())
                                    .defaultValue(p.getDefaultValue())
                                    .required(p.getRequired())
                                    .build());
            String desc =
                    blank(method.getMethodDescription())
                            ? (blank(method.getDisplayName())
                                    ? method.getMethodName()
                                    : method.getDisplayName())
                            : method.getMethodDescription();
            result.add(
                    HttpPluginMethod.builder()
                            .methodName(prefix + "__" + method.getMethodName())
                            .methodDescription(desc)
                            .httpMethodType(method.getHttpMethodType())
                            .uri(method.getUri())
                            .parameters(params)
                            .build());
        }
        return HttpPlugin.builder()
                .baseUrl(normalizeBaseUrl(plugin.getBaseUrl()))
                .staticHeaders(readHeaders(plugin.getStaticHeaders()))
                .pluginMethods(result)
                .build();
    }

    public Map<String, String> readHeaders(String json) {
        if (blank(json)) return new LinkedHashMap<String, String>();
        try {
            return objectMapper.readValue(
                    json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("静态请求头 JSON 解析失败", e);
        }
    }

    public String writeHeaders(Map<String, String> headers) {
        try {
            return objectMapper.writeValueAsString(
                    headers == null ? Collections.emptyMap() : headers);
        } catch (Exception e) {
            throw new IllegalArgumentException("静态请求头 JSON 序列化失败", e);
        }
    }

    public String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
