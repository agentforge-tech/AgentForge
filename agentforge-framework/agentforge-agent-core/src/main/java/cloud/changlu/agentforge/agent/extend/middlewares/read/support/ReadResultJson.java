package cloud.changlu.agentforge.agent.extend.middlewares.read.support;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResourceKind;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReadBatchResult 与标准 JSON 的互转。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ReadResultJson {

    private ReadResultJson() {}

    public static String toJson(ReadBatchResult batch) {
        if (batch == null) {
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema", batch.getSchema());
        root.put("ok", batch.isOk());
        List<Object> results = new ArrayList<Object>();
        for (ReadResult item : batch.getResults()) {
            results.add(toMap(item));
        }
        root.put("results", results);
        if (batch.getErrorCode() != null) {
            root.put("errorCode", batch.getErrorCode());
        }
        if (batch.getMessage() != null) {
            root.put("message", batch.getMessage());
        }
        return Json.stringify(root);
    }

    public static ReadBatchResult parseBatch(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            if (root == null) {
                return null;
            }
            ReadBatchResult batch = new ReadBatchResult();
            batch.setSchema(Json.string(root.get("schema")));
            batch.setOk(booleanValue(root.get("ok"), true));
            batch.setErrorCode(Json.string(root.get("errorCode")));
            batch.setMessage(Json.string(root.get("message")));
            List<ReadResult> items = new ArrayList<ReadResult>();
            List<Object> results = Json.array(root.get("results"));
            if (results != null) {
                for (Object value : results) {
                    Map<String, Object> map = Json.object(value);
                    if (map != null) {
                        items.add(fromMap(map));
                    }
                }
            }
            batch.setResults(items);
            return batch;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> toMap(ReadResult item) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("ok", item.isOk());
        if (item.getErrorCode() != null) {
            map.put("errorCode", item.getErrorCode());
        }
        if (item.getMessage() != null) {
            map.put("message", item.getMessage());
        }
        map.put("kind", item.getKind() == null ? null : item.getKind().code());
        map.put("url", item.getUrl());
        map.put("name", item.getName());
        map.put("mimeType", item.getMimeType());
        map.put("fileType", item.getFileType());
        map.put("size", item.getSize());
        map.put("source", item.getSource());
        map.put("text", item.getText());
        map.put("dedupeKey", item.getDedupeKey());
        map.put("extra", item.getExtra());
        return map;
    }

    private static ReadResult fromMap(Map<String, Object> map) {
        ReadResult item = new ReadResult();
        item.setOk(booleanValue(map.get("ok"), true));
        item.setErrorCode(Json.string(map.get("errorCode")));
        item.setMessage(Json.string(map.get("message")));
        item.setKind(ReadResourceKind.fromCode(Json.string(map.get("kind"))));
        item.setUrl(Json.string(map.get("url")));
        item.setName(Json.string(map.get("name")));
        item.setMimeType(Json.string(map.get("mimeType")));
        item.setFileType(Json.string(map.get("fileType")));
        Object size = map.get("size");
        if (size instanceof Number) {
            item.setSize(((Number) size).longValue());
        }
        item.setSource(Json.string(map.get("source")));
        item.setText(Json.string(map.get("text")));
        item.setDedupeKey(Json.string(map.get("dedupeKey")));
        item.setExtra(Json.object(map.get("extra")));
        return item;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
}
