package cloud.changlu.agentforge.service.modules.plugins.http.support;

import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.HttpPluginMethodSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.HttpPluginParameterReq;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.HttpPluginSaveReq;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class HttpPluginValidator {
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,15}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");
    private static final Pattern PARAM = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> BLOCKED_HEADERS = new HashSet<String>();

    static {
        BLOCKED_HEADERS.add("host");
        BLOCKED_HEADERS.add("content-length");
        BLOCKED_HEADERS.add("transfer-encoding");
        BLOCKED_HEADERS.add("connection");
    }

    public void validatePlugin(HttpPluginSaveReq req) {
        if (req == null || blank(req.getName()))
            throw new IllegalArgumentException("HTTP 插件名称不能为空");
        if (blank(req.getAlias()) || !ALIAS.matcher(req.getAlias().trim()).matches())
            throw new IllegalArgumentException("插件别名需以字母开头，且只能包含字母、数字和下划线（2-16位）");
        if (blank(req.getBaseUrl())) throw new IllegalArgumentException("服务基础地址不能为空");
        try {
            URI uri = URI.create(req.getBaseUrl().trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || blank(uri.getHost())) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new IllegalArgumentException("服务基础地址必须是合法的 HTTP/HTTPS URL");
        }
        if (req.getStaticHeaders() != null)
            for (java.util.Map.Entry<String, String> entry : req.getStaticHeaders().entrySet())
                validateHeader(entry.getKey(), entry.getValue());
    }

    public void validateMethod(HttpPluginMethodSaveReq req) {
        if (req == null || req.getPluginId() == null)
            throw new IllegalArgumentException("所属 HTTP 插件不能为空");
        if (blank(req.getMethodName())
                || !NAME.matcher(req.getMethodName().trim()).matches()
                || req.getMethodName().contains("__"))
            throw new IllegalArgumentException("工具名需以字母开头，且只能包含字母、数字和下划线");
        if (req.getHttpMethodType() == null) throw new IllegalArgumentException("请选择 HTTP 方法");
        HttpPluginEnums.HttpMethod.fromValue(req.getHttpMethodType());
        if (blank(req.getUri()) || !req.getUri().trim().startsWith("/"))
            throw new IllegalArgumentException("请求 URI 必须以 / 开头");
        validateParameters(req);
    }

    private void validateParameters(HttpPluginMethodSaveReq req) {
        List<HttpPluginParameterReq> params = req.getParameters();
        if (params == null) return;
        Set<String> names = new HashSet<String>();
        Set<String> bodyNames = new HashSet<String>();
        for (HttpPluginParameterReq p : params) {
            if (p == null
                    || blank(p.getMethodParamName())
                    || !PARAM.matcher(p.getMethodParamName().trim()).matches())
                throw new IllegalArgumentException("参数名不合法");
            if (!names.add(p.getMethodParamName().trim()))
                throw new IllegalArgumentException("参数名重复: " + p.getMethodParamName());
            if (blank(p.getMappedName())) throw new IllegalArgumentException("参数映射名不能为空");
            HttpPluginEnums.ParameterUseType use =
                    HttpPluginEnums.ParameterUseType.fromValue(p.getUseTypeValue());
            HttpPluginEnums.ParameterType type =
                    HttpPluginEnums.ParameterType.fromValue(p.getDataTypeValue());
            if ((use == HttpPluginEnums.ParameterUseType.QUERY
                            || use == HttpPluginEnums.ParameterUseType.PATH
                            || use == HttpPluginEnums.ParameterUseType.HEADER)
                    && (type == HttpPluginEnums.ParameterType.ARRAY
                            || type == HttpPluginEnums.ParameterType.OBJECT))
                throw new IllegalArgumentException("Query/Path/Header 参数只支持基础类型");
            if ((req.getHttpMethodType() == 1 || req.getHttpMethodType() == 4)
                    && use == HttpPluginEnums.ParameterUseType.BODY)
                throw new IllegalArgumentException("GET/DELETE 方法不允许配置 Body 参数");
            if (use == HttpPluginEnums.ParameterUseType.PATH
                    && !req.getUri().contains("{" + p.getMappedName() + "}"))
                throw new IllegalArgumentException(
                        "Path参数 " + p.getMappedName() + " 在URI中缺少占位符 {" + p.getMappedName() + "}");
            if (use == HttpPluginEnums.ParameterUseType.HEADER)
                validateHeader(p.getMappedName(), p.getDefaultValue());
            if (use == HttpPluginEnums.ParameterUseType.BODY && !bodyNames.add(p.getMappedName()))
                throw new IllegalArgumentException("Body 参数映射名重复: " + p.getMappedName());
        }
    }

    private void validateHeader(String name, String value) {
        if (blank(name)
                || !name.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
                || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("不允许的 Header: " + name);
        if (value != null && (value.contains("\r") || value.contains("\n")))
            throw new IllegalArgumentException("Header 值不允许包含换行符");
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
