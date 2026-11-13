package cloud.changlu.agentforge.service.modules.plugins.http.service.impl;

import cloud.changlu.agentforge.agent.tool.http.HttpToolFactory;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.parser.curl.CurlParser;
import cloud.changlu.agentforge.agent.tool.http.parser.curl.CurlToHttpPluginConverter;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.http.dao.*;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.*;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.*;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.vo.*;
import cloud.changlu.agentforge.service.modules.plugins.http.service.HttpPluginAgentCacheService;
import cloud.changlu.agentforge.service.modules.plugins.http.service.IHttpPluginService;
import cloud.changlu.agentforge.service.modules.plugins.http.support.HttpPluginConverter;
import cloud.changlu.agentforge.service.modules.plugins.http.support.HttpPluginValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class HttpPluginServiceImpl implements IHttpPluginService {
    private static final long USER_ID = 1L;
    private static final String MASK = "******";
    private static final Set<String> SENSITIVE =
            new HashSet<String>(
                    Arrays.asList(
                            "authorization",
                            "cookie",
                            "set-cookie",
                            "x-api-key",
                            "x-auth-token",
                            "proxy-authorization"));
    private final HttpPluginMapper pluginMapper;
    private final HttpPluginMethodMapper methodMapper;
    private final HttpPluginParameterMapper parameterMapper;
    private final HttpPluginValidator validator;
    private final HttpPluginConverter converter;
    private final HttpPluginAgentCacheService agentCacheService;
    private final ObjectMapper objectMapper;

    public HttpPluginServiceImpl(
            HttpPluginMapper pluginMapper,
            HttpPluginMethodMapper methodMapper,
            HttpPluginParameterMapper parameterMapper,
            HttpPluginValidator validator,
            HttpPluginConverter converter,
            HttpPluginAgentCacheService agentCacheService,
            ObjectMapper objectMapper) {
        this.pluginMapper = pluginMapper;
        this.methodMapper = methodMapper;
        this.parameterMapper = parameterMapper;
        this.validator = validator;
        this.converter = converter;
        this.agentCacheService = agentCacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<List<HttpPluginVo>> page(
            String keyword, Integer status, Integer page, Integer pageSize) {
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? 12 : Math.min(pageSize, 100);
        String kw = trim(keyword);
        long total = pluginMapper.selectCount(kw, status);
        List<HttpPluginVo> data = new ArrayList<HttpPluginVo>();
        if (total > 0)
            for (HttpPluginPojo pojo :
                    pluginMapper.selectPage(kw, status, (current - 1) * size, size))
                data.add(toPluginVo(pojo, false, false));
        return new PageResult<List<HttpPluginVo>>(current, size, total, data);
    }

    @Override
    public HttpPluginVo detail(Long id) {
        return toPluginVo(requirePlugin(id), true, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HttpPluginVo savePlugin(HttpPluginSaveReq req) {
        validator.validatePlugin(req);
        Long exclude = req.getId();
        String alias = req.getAlias().trim();
        if (pluginMapper.countByAlias(alias, USER_ID, exclude) > 0)
            throw new IllegalArgumentException("同一用户下插件别名已存在: " + alias);
        Date now = new Date();
        HttpPluginPojo pojo = new HttpPluginPojo();
        pojo.setId(req.getId());
        pojo.setName(req.getName().trim());
        pojo.setAlias(alias);
        pojo.setBaseUrl(converter.normalizeBaseUrl(req.getBaseUrl()));
        pojo.setDescription(value(req.getDescription()));
        pojo.setIcon(value(req.getIcon()));
        pojo.setStatus(status(req.getStatus()));
        pojo.setModifyTime(now);
        Map<String, String> headers = mergeHeaders(req, exclude);
        pojo.setStaticHeaders(converter.writeHeaders(headers));
        if (exclude == null) {
            pojo.setIsPublic(0);
            pojo.setCreateUserId(USER_ID);
            pojo.setIsDeleted(0);
            pojo.setCreateTime(now);
            pluginMapper.insert(pojo);
        } else {
            requirePlugin(exclude);
            pluginMapper.update(pojo);
            agentCacheService.evictAllAfterCommit();
        }
        return detail(pojo.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePluginStatus(Long id, Integer status) {
        requirePlugin(id);
        pluginMapper.updateStatus(id, status(status));
        agentCacheService.evictAllAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePlugin(Long id) {
        requirePlugin(id);
        agentCacheService.evictAllAfterCommit();
        parameterMapper.deleteByPluginId(id);
        methodMapper.deleteByPluginId(id);
        pluginMapper.deleteById(id);
    }

    @Override
    public List<HttpPluginMethodVo> methods(Long pluginId, boolean onlyEnabled) {
        HttpPluginPojo plugin = requirePlugin(pluginId);
        List<HttpPluginMethodVo> result = new ArrayList<HttpPluginMethodVo>();
        for (HttpPluginMethodPojo pojo : methodMapper.selectByPluginId(pluginId, onlyEnabled))
            result.add(toMethodVo(pojo, plugin.getAlias(), false));
        return result;
    }

    @Override
    public HttpPluginMethodVo methodDetail(Long id) {
        HttpPluginMethodPojo method = requireMethod(id);
        HttpPluginPojo plugin = requirePlugin(method.getPluginId());
        return toMethodVo(method, plugin.getAlias(), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HttpPluginMethodVo saveMethod(HttpPluginMethodSaveReq req) {
        validator.validateMethod(req);
        HttpPluginPojo plugin = requirePlugin(req.getPluginId());
        if (methodMapper.countByName(req.getPluginId(), req.getMethodName().trim(), req.getId())
                > 0) throw new IllegalArgumentException("同一插件下工具名已存在: " + req.getMethodName());
        Date now = new Date();
        HttpPluginMethodPojo pojo = toMethodPojo(req, now);
        if (req.getId() == null) {
            pojo.setIsDeleted(0);
            pojo.setCreateTime(now);
            methodMapper.insert(pojo);
        } else {
            HttpPluginMethodPojo exists = requireMethod(req.getId());
            if (!exists.getPluginId().equals(req.getPluginId()))
                throw new IllegalArgumentException("不能修改工具所属插件");
            methodMapper.update(pojo);
            parameterMapper.deleteByMethodId(pojo.getId());
        }
        insertParams(pojo.getId(), req.getParameters(), now);
        agentCacheService.evictAllAfterCommit();
        return methodDetail(pojo.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMethodStatus(Long id, Integer status) {
        HttpPluginMethodPojo method = requireMethod(id);
        methodMapper.updateStatus(id, status(status));
        agentCacheService.evictAllAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMethod(Long id) {
        HttpPluginMethodPojo method = requireMethod(id);
        parameterMapper.deleteByMethodId(id);
        methodMapper.deleteById(id);
        agentCacheService.evictAllAfterCommit();
    }

    @Override
    public HttpPluginTestRunVo testRun(Long id, HttpPluginTestRunReq req) {
        HttpPluginMethodPojo method = requireMethod(id);
        HttpPluginPojo plugin = requirePlugin(method.getPluginId());
        Map<Long, List<HttpPluginParameterPojo>> grouped =
                new LinkedHashMap<Long, List<HttpPluginParameterPojo>>();
        grouped.put(id, parameterMapper.selectByMethodId(id));
        HttpPlugin runtime =
                converter.convert(plugin, Collections.singletonList(method), grouped, null);
        Map<ToolSpecification, ToolExecutor> tools = HttpToolFactory.buildHttpTools(runtime);
        Map.Entry<ToolSpecification, ToolExecutor> entry = tools.entrySet().iterator().next();
        HttpPluginTestRunVo vo = new HttpPluginTestRunVo();
        vo.setToolName(entry.getKey().name());
        long start = System.currentTimeMillis();
        try {
            String args =
                    objectMapper.writeValueAsString(
                            req == null || req.getArguments() == null
                                    ? Collections.emptyMap()
                                    : req.getArguments());
            vo.setResult(
                    entry.getValue()
                            .execute(
                                    ToolExecutionRequest.from(
                                            "http-plugin-test", entry.getKey().name(), args),
                                    "http-plugin-test"));
            vo.setSuccess(true);
        } catch (Exception e) {
            vo.setSuccess(false);
            vo.setError(e.getMessage());
        }
        vo.setDurationMs(System.currentTimeMillis() - start);
        return vo;
    }

    @Override
    public HttpPluginVo previewCurl(HttpPluginCurlReq req) {
        HttpPlugin parsed = parseCurl(req);
        HttpPluginVo vo = new HttpPluginVo();
        vo.setBaseUrl(parsed.getBaseUrl());
        vo.setStaticHeaders(mask(parsed.getStaticHeaders(), new ArrayList<String>()));
        vo.setName(req == null ? null : req.getPluginName());
        vo.setDescription(req == null ? null : req.getPluginDescription());
        List<HttpPluginMethodVo> methods = new ArrayList<HttpPluginMethodVo>();
        for (HttpPluginMethod method : parsed.getPluginMethods())
            methods.add(fromFramework(method, req));
        vo.setMethods(methods);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HttpPluginVo importCurl(HttpPluginCurlReq req) {
        HttpPlugin parsed = parseCurl(req);
        Long pluginId = req.getPluginId();
        if (pluginId == null) {
            HttpPluginSaveReq save = new HttpPluginSaveReq();
            save.setName(blank(req.getPluginName()) ? "CURL 导入插件" : req.getPluginName());
            save.setAlias(nextImportAlias(req.getPluginName()));
            save.setBaseUrl(parsed.getBaseUrl());
            save.setStaticHeaders(parsed.getStaticHeaders());
            save.setDescription(value(req.getPluginDescription()));
            save.setStatus(1);
            pluginId = savePlugin(save).getId();
        }
        HttpPluginMethod framework = parsed.getPluginMethods().get(0);
        HttpPluginMethodSaveReq method = toSaveReq(framework, req, pluginId);
        saveMethod(method);
        return detail(pluginId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HttpPluginVo importJson(HttpPluginJsonImportReq req) {
        if (req == null || req.getPlugin() == null)
            throw new IllegalArgumentException("JSON 文件缺少 plugin 配置");
        HttpPluginJsonImportReq.Plugin source = req.getPlugin();
        if (blank(source.getName())) throw new IllegalArgumentException("插件名称不能为空");
        if (blank(source.getBaseUrl())) throw new IllegalArgumentException("Base URL 不能为空");

        HttpPluginSaveReq plugin = new HttpPluginSaveReq();
        plugin.setName(source.getName());
        plugin.setAlias(
                blank(source.getAlias())
                        ? nextImportAlias(source.getName())
                        : nextImportAlias(source.getAlias()));
        plugin.setBaseUrl(source.getBaseUrl());
        plugin.setStaticHeaders(source.getStaticHeaders());
        plugin.setDescription(value(source.getDescription()));
        plugin.setIcon(value(source.getIcon()));
        plugin.setStatus(source.getStatus() == null ? 1 : source.getStatus());
        Long pluginId = savePlugin(plugin).getId();

        if (source.getMethods() != null) {
            for (HttpPluginJsonImportReq.Method item : source.getMethods()) {
                if (item == null) continue;
                HttpPluginMethodSaveReq method = new HttpPluginMethodSaveReq();
                method.setPluginId(pluginId);
                method.setMethodName(normalizeMethodName(item.getMethodName()));
                method.setDisplayName(
                        blank(item.getDisplayName())
                                ? method.getMethodName()
                                : item.getDisplayName());
                method.setMethodDescription(value(item.getMethodDescription()));
                method.setHttpMethodType(item.getHttpMethodType());
                method.setUri(item.getUri());
                method.setStatus(item.getStatus() == null ? 1 : item.getStatus());
                method.setSortOrder(item.getSortOrder());
                method.setParameters(item.getParameters());
                saveMethod(method);
            }
        }
        return detail(pluginId);
    }

    @Override
    public HttpPluginJsonImportReq exportJson(Long id) {
        HttpPluginPojo pojo = requirePlugin(id);
        HttpPluginJsonImportReq result = new HttpPluginJsonImportReq();
        result.setVersion("1.0");
        result.setSource("manual");
        HttpPluginJsonImportReq.Plugin plugin = new HttpPluginJsonImportReq.Plugin();
        plugin.setName(pojo.getName());
        plugin.setAlias(pojo.getAlias());
        plugin.setIcon(pojo.getIcon());
        plugin.setBaseUrl(pojo.getBaseUrl());
        plugin.setDescription(pojo.getDescription());
        plugin.setStatus(pojo.getStatus());
        Map<String, String> headers = converter.readHeaders(pojo.getStaticHeaders());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (SENSITIVE.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                entry.setValue(
                        "{{" + entry.getKey().toUpperCase(Locale.ROOT).replace('-', '_') + "}}");
        }
        plugin.setStaticHeaders(headers);
        List<HttpPluginJsonImportReq.Method> methods =
                new ArrayList<HttpPluginJsonImportReq.Method>();
        for (HttpPluginMethodPojo method : methodMapper.selectByPluginId(id, false)) {
            HttpPluginJsonImportReq.Method item = new HttpPluginJsonImportReq.Method();
            item.setMethodName(method.getMethodName());
            item.setDisplayName(method.getDisplayName());
            item.setMethodDescription(method.getMethodDescription());
            item.setHttpMethodType(method.getHttpMethodType());
            item.setUri(method.getUri());
            item.setStatus(method.getStatus());
            item.setSortOrder(method.getSortOrder());
            item.setParameters(toReqs(parameterMapper.selectByMethodId(method.getId())));
            methods.add(item);
        }
        plugin.setMethods(methods);
        result.setPlugin(plugin);
        return result;
    }

    @Override
    public List<HttpPluginVo> options() {
        List<HttpPluginPojo> plugins = pluginMapper.selectPage(null, 1, 0, 1000);
        List<HttpPluginVo> result = new ArrayList<HttpPluginVo>();
        for (HttpPluginPojo plugin : plugins) {
            HttpPluginVo vo = toPluginVo(plugin, false, false);
            vo.setMethods(methods(plugin.getId(), true));
            result.add(vo);
        }
        return result;
    }

    private HttpPlugin parseCurl(HttpPluginCurlReq req) {
        if (req == null || blank(req.getCurlCommand()))
            throw new IllegalArgumentException("curl 命令不能为空");
        HttpPlugin parsed =
                new CurlToHttpPluginConverter()
                        .convert(new CurlParser().parse(req.getCurlCommand()));
        if (blank(parsed.getBaseUrl())
                || parsed.getPluginMethods() == null
                || parsed.getPluginMethods().isEmpty())
            throw new IllegalArgumentException("curl 命令中未找到有效 URL");
        return parsed;
    }

    private HttpPluginMethodSaveReq toSaveReq(
            HttpPluginMethod method, HttpPluginCurlReq req, Long pluginId) {
        HttpPluginMethodSaveReq save = new HttpPluginMethodSaveReq();
        save.setPluginId(pluginId);
        save.setMethodName(normalizeMethodName(method.getMethodName()));
        save.setDisplayName(save.getMethodName());
        save.setMethodDescription(method.getMethodDescription());
        save.setHttpMethodType(method.getHttpMethodType());
        save.setUri(method.getUri());
        save.setStatus(1);
        save.setParameters(toParameterReqs(method.getParameters()));
        return save;
    }

    private HttpPluginMethodVo fromFramework(HttpPluginMethod method, HttpPluginCurlReq req) {
        HttpPluginMethodVo vo = new HttpPluginMethodVo();
        vo.setMethodName(normalizeMethodName(method.getMethodName()));
        vo.setDisplayName(vo.getMethodName());
        vo.setMethodDescription(method.getMethodDescription());
        vo.setHttpMethodType(method.getHttpMethodType());
        vo.setUri(method.getUri());
        vo.setStatus(1);
        vo.setParameters(toParameterReqs(method.getParameters()));
        return vo;
    }

    private List<HttpPluginParameterReq> toParameterReqs(List<HttpToolParameter> params) {
        List<HttpPluginParameterReq> result = new ArrayList<HttpPluginParameterReq>();
        if (params == null) return result;
        int order = 0;
        for (HttpToolParameter p : params) {
            HttpPluginParameterReq item = new HttpPluginParameterReq();
            item.setMethodParamName(normalizeParamName(p.getMethodParamName()));
            item.setMethodParamDescription(p.getMethodParamDescription());
            item.setMappedName(p.getMappedName());
            item.setUseTypeValue(p.getUseTypeValue());
            item.setDataTypeValue(p.getDataTypeValue());
            item.setDefaultValue(
                    p.getDefaultValue() == null ? null : String.valueOf(p.getDefaultValue()));
            item.setRequired(p.getRequired());
            item.setSortOrder(order++);
            result.add(item);
        }
        return result;
    }

    private HttpPluginPojo requirePlugin(Long id) {
        if (id == null) throw new IllegalArgumentException("插件ID不能为空");
        HttpPluginPojo pojo = pluginMapper.selectById(id);
        if (pojo == null) throw new IllegalArgumentException("HTTP 插件不存在或已删除");
        return pojo;
    }

    private HttpPluginMethodPojo requireMethod(Long id) {
        if (id == null) throw new IllegalArgumentException("工具ID不能为空");
        HttpPluginMethodPojo pojo = methodMapper.selectById(id);
        if (pojo == null) throw new IllegalArgumentException("HTTP 工具不存在或已删除");
        return pojo;
    }

    private HttpPluginVo toPluginVo(HttpPluginPojo p, boolean headers, boolean includeMethods) {
        HttpPluginVo vo = new HttpPluginVo();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setAlias(p.getAlias());
        vo.setBaseUrl(p.getBaseUrl());
        vo.setDescription(p.getDescription());
        vo.setIcon(p.getIcon());
        vo.setStatus(p.getStatus());
        vo.setMethodCount(pluginMapper.countMethods(p.getId()));
        vo.setCreateTime(p.getCreateTime());
        vo.setModifyTime(p.getModifyTime());
        if (headers) {
            List<String> sensitive = new ArrayList<String>();
            vo.setStaticHeaders(mask(converter.readHeaders(p.getStaticHeaders()), sensitive));
            vo.setSensitiveHeaderKeys(sensitive);
        }
        if (includeMethods) vo.setMethods(methods(p.getId(), false));
        return vo;
    }

    private HttpPluginMethodVo toMethodVo(HttpPluginMethodPojo p, String alias, boolean params) {
        HttpPluginMethodVo vo = new HttpPluginMethodVo();
        vo.setId(p.getId());
        vo.setPluginId(p.getPluginId());
        vo.setMethodName(p.getMethodName());
        vo.setDisplayName(p.getDisplayName());
        vo.setMethodDescription(p.getMethodDescription());
        vo.setHttpMethodType(p.getHttpMethodType());
        vo.setUri(p.getUri());
        vo.setStatus(p.getStatus());
        vo.setSortOrder(p.getSortOrder());
        vo.setToolName(alias + "__" + p.getMethodName());
        vo.setCreateTime(p.getCreateTime());
        vo.setModifyTime(p.getModifyTime());
        if (params) vo.setParameters(toReqs(parameterMapper.selectByMethodId(p.getId())));
        return vo;
    }

    private HttpPluginMethodPojo toMethodPojo(HttpPluginMethodSaveReq req, Date now) {
        HttpPluginMethodPojo p = new HttpPluginMethodPojo();
        p.setId(req.getId());
        p.setPluginId(req.getPluginId());
        p.setMethodName(req.getMethodName().trim());
        p.setDisplayName(value(req.getDisplayName()));
        p.setMethodDescription(value(req.getMethodDescription()));
        p.setHttpMethodType(req.getHttpMethodType());
        p.setUri(req.getUri().trim());
        p.setStatus(status(req.getStatus()));
        p.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        p.setModifyTime(now);
        return p;
    }

    private void insertParams(Long methodId, List<HttpPluginParameterReq> params, Date now) {
        if (params == null) return;
        int order = 0;
        for (HttpPluginParameterReq req : params) {
            HttpPluginParameterPojo p = new HttpPluginParameterPojo();
            p.setMethodId(methodId);
            p.setMethodParamName(req.getMethodParamName().trim());
            p.setMethodParamDescription(value(req.getMethodParamDescription()));
            p.setMappedName(req.getMappedName().trim());
            p.setUseTypeValue(req.getUseTypeValue());
            p.setDataTypeValue(req.getDataTypeValue());
            p.setDefaultValue(req.getDefaultValue());
            p.setRequired(req.getRequired() == null ? 0 : req.getRequired());
            p.setSortOrder(req.getSortOrder() == null ? order : req.getSortOrder());
            p.setIsDeleted(0);
            p.setCreateTime(now);
            p.setModifyTime(now);
            parameterMapper.insert(p);
            order++;
        }
    }

    private List<HttpPluginParameterReq> toReqs(List<HttpPluginParameterPojo> params) {
        List<HttpPluginParameterReq> result = new ArrayList<HttpPluginParameterReq>();
        for (HttpPluginParameterPojo p : params) {
            HttpPluginParameterReq r = new HttpPluginParameterReq();
            r.setId(p.getId());
            r.setMethodParamName(p.getMethodParamName());
            r.setMethodParamDescription(p.getMethodParamDescription());
            r.setMappedName(p.getMappedName());
            r.setUseTypeValue(p.getUseTypeValue());
            r.setDataTypeValue(p.getDataTypeValue());
            r.setDefaultValue(p.getDefaultValue());
            r.setRequired(p.getRequired());
            r.setSortOrder(p.getSortOrder());
            result.add(r);
        }
        return result;
    }

    private Map<String, String> mergeHeaders(HttpPluginSaveReq req, Long id) {
        Map<String, String> old =
                id == null
                        ? Collections.emptyMap()
                        : converter.readHeaders(requirePlugin(id).getStaticHeaders());
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (req.getStaticHeaders() != null)
            for (Map.Entry<String, String> e : req.getStaticHeaders().entrySet())
                result.put(
                        e.getKey(), MASK.equals(e.getValue()) ? old.get(e.getKey()) : e.getValue());
        return result;
    }

    private Map<String, String> mask(Map<String, String> headers, List<String> keys) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (SENSITIVE.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                result.put(e.getKey(), MASK);
                keys.add(e.getKey());
            } else result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    private int status(Integer v) {
        if (v == null) return 1;
        if (v != 0 && v != 1) throw new IllegalArgumentException("状态只能是 0 或 1");
        return v;
    }

    private String normalizeMethodName(String v) {
        String n = v == null ? "execute" : v.replaceAll("[^A-Za-z0-9_]", "_");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = "execute_" + n;
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    private String nextImportAlias(String pluginName) {
        String seed = blank(pluginName) ? "http_api" : pluginName.trim().toLowerCase(Locale.ROOT);
        seed = seed.replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        seed = seed.replaceAll("^_+|_+$", "");
        if (seed.isEmpty() || !Character.isLetter(seed.charAt(0))) seed = "http_" + seed;
        if (seed.length() < 2) seed = seed + "_api";
        if (seed.length() > 16) seed = seed.substring(0, 16);
        String candidate = seed;
        int suffix = 2;
        while (pluginMapper.countByAlias(candidate, USER_ID, null) > 0) {
            String tail = "_" + suffix++;
            candidate = seed.substring(0, Math.min(seed.length(), 16 - tail.length())) + tail;
        }
        return candidate;
    }

    private String normalizeParamName(String v) {
        String n = v == null ? "param" : v.replaceAll("[^A-Za-z0-9_]", "_");
        if (n.isEmpty() || (!(Character.isLetter(n.charAt(0))) && n.charAt(0) != '_'))
            n = "param_" + n;
        return n;
    }

    private String trim(String v) {
        return blank(v) ? null : v.trim();
    }

    private String value(String v) {
        return v == null ? "" : v.trim();
    }

    private boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
