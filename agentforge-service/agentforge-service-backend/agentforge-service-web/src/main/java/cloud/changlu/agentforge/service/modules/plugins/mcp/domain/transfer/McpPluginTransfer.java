package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.transfer;

import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginServerPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpPluginSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerSaveReq;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpPluginVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerDetailVo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo.McpServerVo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 插件 / Server 的对象转换与 Headers 序列化工具。
 *
 * @author changlu
 * @since 2026-10-27
 */
public final class McpPluginTransfer {

    private static final TypeReference<Map<String, String>> HEADERS_TYPE =
            new TypeReference<Map<String, String>>() {};

    /** 需要脱敏的 Header 名称片段，命中后只保留首尾少量字符。 */
    private static final String[] SENSITIVE_HEADER_KEYWORDS = {
        "authorization", "token", "api-key", "apikey", "secret", "cookie", "password"
    };

    private McpPluginTransfer() {}

    public static McpPluginPojo toPluginPojo(McpPluginSaveReq req) {
        McpPluginPojo pojo = new McpPluginPojo();
        pojo.setId(req.getId());
        pojo.setName(req.getName() == null ? null : req.getName().trim());
        pojo.setDescription(trimToNull(req.getDescription()));
        pojo.setIcon(trimToNull(req.getIcon()));
        pojo.setStatus(req.getStatus());
        return pojo;
    }

    public static McpPluginServerPojo toServerPojo(
            McpServerSaveReq req, ObjectMapper objectMapper) {
        McpPluginServerPojo pojo = new McpPluginServerPojo();
        pojo.setId(req.getId());
        pojo.setPluginId(req.getPluginId());
        pojo.setServerName(req.getServerName() == null ? null : req.getServerName().trim());
        pojo.setServerUrl(req.getServerUrl() == null ? null : req.getServerUrl().trim());
        pojo.setTransportType(req.getTransportType());
        pojo.setHeaders(writeHeaders(req.getHeaders(), objectMapper));
        pojo.setStatus(req.getStatus());
        return pojo;
    }

    public static McpPluginVo toPluginVo(McpPluginPojo pojo, List<McpServerVo> servers) {
        if (pojo == null) {
            return null;
        }
        McpPluginVo vo = new McpPluginVo();
        vo.setId(pojo.getId());
        vo.setName(pojo.getName());
        vo.setDescription(pojo.getDescription());
        vo.setIcon(pojo.getIcon());
        vo.setStatus(pojo.getStatus());
        vo.setCreateUserId(pojo.getCreateUserId());
        vo.setCreateTime(pojo.getCreateTime());
        vo.setModifyTime(pojo.getModifyTime());
        vo.setServers(servers);
        return vo;
    }

    public static McpServerVo toServerVo(McpPluginServerPojo pojo, ObjectMapper objectMapper) {
        if (pojo == null) {
            return null;
        }
        McpServerVo vo = new McpServerVo();
        fillServerVo(vo, pojo, objectMapper);
        vo.setHeaders(maskHeaders(readHeaders(pojo.getHeaders(), objectMapper)));
        return vo;
    }

    public static McpServerDetailVo toServerDetailVo(
            McpPluginServerPojo pojo, ObjectMapper objectMapper) {
        if (pojo == null) {
            return null;
        }
        McpServerDetailVo vo = new McpServerDetailVo();
        fillServerVo(vo, pojo, objectMapper);
        Map<String, String> headers = readHeaders(pojo.getHeaders(), objectMapper);
        vo.setHeaders(maskHeaders(headers));
        vo.setRawHeaders(headers);
        return vo;
    }

    public static List<McpServerVo> toServerVoList(
            List<McpPluginServerPojo> pojos, ObjectMapper objectMapper) {
        if (pojos == null || pojos.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpServerVo> result = new ArrayList<McpServerVo>(pojos.size());
        for (McpPluginServerPojo pojo : pojos) {
            result.add(toServerVo(pojo, objectMapper));
        }
        return result;
    }

    public static String writeHeaders(Map<String, String> headers, ObjectMapper objectMapper) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalArgumentException("headers 序列化失败", e);
        }
    }

    public static Map<String, String> readHeaders(String json, ObjectMapper objectMapper) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<String, String>();
        }
        try {
            Map<String, String> headers = objectMapper.readValue(json, HEADERS_TYPE);
            return headers == null ? new LinkedHashMap<String, String>() : headers;
        } catch (Exception e) {
            throw new IllegalArgumentException("headers 配置格式错误", e);
        }
    }

    /** 对敏感 Header 值脱敏，保留首尾各 4 个字符。 */
    public static Map<String, String> maskHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> masked = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            masked.put(entry.getKey(), maskValue(entry.getKey(), entry.getValue()));
        }
        return masked;
    }

    private static String maskValue(String key, String value) {
        if (value == null || !isSensitiveKey(key)) {
            return value;
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase();
        for (String keyword : SENSITIVE_HEADER_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static void fillServerVo(
            McpServerVo vo, McpPluginServerPojo pojo, ObjectMapper objectMapper) {
        vo.setId(pojo.getId());
        vo.setPluginId(pojo.getPluginId());
        vo.setServerName(pojo.getServerName());
        vo.setServerUrl(pojo.getServerUrl());
        vo.setTransportType(pojo.getTransportType());
        vo.setStatus(pojo.getStatus());
        vo.setCreateUserId(pojo.getCreateUserId());
        vo.setCreateTime(pojo.getCreateTime());
        vo.setModifyTime(pojo.getModifyTime());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
