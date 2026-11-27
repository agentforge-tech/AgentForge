package cloud.changlu.agentforge.model.openai;

import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Completions（FIM）wire 协议辅助类。
 *
 * <p>与 {@link OpenAiMessages} 同一约定：包私有，仅服务于 {@link OpenAiFimModel} 与 {@link
 * OpenAiStreamingFimModel}，不属于公开 API。抽出来后两个 Provider 共享同一份 请求体构造与响应解析逻辑，避免同步/流式实现漂移。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class OpenAiFimWire {

    private OpenAiFimWire() {}

    /**
     * 构造 Completions 请求体。
     *
     * @param streaming 流式时附加 {@code stream=true} 与 {@code stream_options.include_usage}
     */
    public static Map<String, Object> buildPayload(
            String modelName,
            FimRequest request,
            DefaultFimRequestParameters parameters,
            boolean streaming,
            boolean includeUsage,
            boolean disableThinking) {

        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        if (parameters.customParameters() != null) {
            payload.putAll(parameters.customParameters());
        }
        payload.put("model", modelName);
        payload.put("prompt", request.prefix());

        // suffix 为空时不下发：部分服务对空串处理不一致。
        if (request.suffix() != null && !request.suffix().isEmpty()) {
            payload.put("suffix", request.suffix());
        }
        putIfNotNull(payload, "max_tokens", parameters.maxTokens());
        putIfNotNull(payload, "temperature", parameters.temperature());
        putIfNotNull(payload, "top_p", parameters.topP());
        if (parameters.stopSequences() != null && !parameters.stopSequences().isEmpty()) {
            payload.put("stop", parameters.stopSequences());
        }

        payload.put("stream", Boolean.valueOf(streaming));
        if (streaming && includeUsage) {
            // stream_options 必须与 stream=true 同用，否则部分服务返回 400。
            LinkedHashMap<String, Object> streamOptions = new LinkedHashMap<String, Object>();
            streamOptions.put("include_usage", Boolean.TRUE);
            payload.put("stream_options", streamOptions);
        }

        // DeepSeek 思考模式默认开启，而 FIM 仅支持非思考模式：
        // 不显式关闭会白付思维链 token，甚至直接失败。
        if (disableThinking) {
            LinkedHashMap<String, Object> thinking = new LinkedHashMap<String, Object>();
            thinking.put("type", "disabled");
            payload.put("thinking", thinking);
        }
        return payload;
    }

    private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /** 解析非流式 Completions 响应。 */
    public static FimResponse parseResponse(String body, String fallbackModel, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        List<Object> choices = Json.array(root.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new ModelException("FIM response has no choices", null, body);
        }
        Map<String, Object> choice = Json.object(choices.get(0));
        String text = choice == null ? "" : Json.string(choice.get("text"));
        String model = Json.string(root.get("model"));

        return FimResponse.builder()
                .completion(text)
                .finishReason(mapFinishReason(choice == null ? null : choice.get("finish_reason")))
                .usage(parseUsage(Json.object(root.get("usage"))))
                .model(model == null ? fallbackModel : model)
                .latencyMs(latencyMs)
                .metadata("systemFingerprint", root.get("system_fingerprint"))
                .build();
    }

    /** 归一化 {@code finish_reason}，包含 DeepSeek 的两个扩展取值。 */
    public static FimFinishReason mapFinishReason(Object value) {
        String reason = Json.string(value);
        if (reason == null) {
            return null;
        }
        if ("stop".equals(reason)) {
            return FimFinishReason.STOP;
        }
        if ("length".equals(reason)) {
            return FimFinishReason.LENGTH;
        }
        if ("content_filter".equals(reason)) {
            return FimFinishReason.CONTENT_FILTER;
        }
        // DeepSeek 扩展：必须显式映射，否则会丢失"可重试"信息。
        if ("insufficient_system_resource".equals(reason)) {
            return FimFinishReason.RESOURCE_LIMIT;
        }
        if ("aborted".equals(reason)) {
            return FimFinishReason.ABORTED;
        }
        return FimFinishReason.OTHER;
    }

    /** 归一化 usage：兼容 DeepSeek 与 OpenAI 两种缓存字段位置。 */
    public static FimUsage parseUsage(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }
        long prompt = Json.longValue(usage.get("prompt_tokens"), 0L);
        long completion = Json.longValue(usage.get("completion_tokens"), 0L);
        long total = Json.longValue(usage.get("total_tokens"), prompt + completion);
        long cached = Json.longValue(usage.get("prompt_cache_hit_tokens"), -1L);
        if (cached < 0) {
            Map<String, Object> details = Json.object(usage.get("prompt_tokens_details"));
            cached = details == null ? 0L : Json.longValue(details.get("cached_tokens"), 0L);
        }
        return new FimUsage(prompt, completion, total, cached);
    }

    public static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
