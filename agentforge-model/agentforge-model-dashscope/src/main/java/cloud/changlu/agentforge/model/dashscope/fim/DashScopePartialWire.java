package cloud.changlu.agentforge.model.dashscope.fim;

import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope（阿里云百炼）Partial Mode 的 wire 协议辅助类。
 *
 * <p><b>为什么单独一套，而不复用 {@code OpenAiFimWire}</b>：百炼的 OpenAI 兼容模式只提供 {@code
 * /chat/completions}，<b>没有</b> {@code /completions}，也<b>没有</b> {@code suffix} 字段。 它的 FIM 等价能力是官方的
 * Partial Mode —— 把前缀放进最后一条 {@code role=assistant} 的 消息并设置 {@code partial: true}：
 *
 * <pre>
 * { "model": "qwen3-coder-flash",
 *   "messages": [
 *     { "role": "user", "content": "请补全..." },
 *     { "role": "assistant", "content": "&lt;前缀&gt;", "partial": true }
 *   ] }
 * </pre>
 *
 * <p><b>能力边界（必须清楚）</b>：Partial Mode 只吃前缀，<b>没有 suffix</b>。因此它是 "前缀续写"而非严格意义的 FIM —— 模型看不到后文，"知道后面已有
 * return 而不重复生成" 这一核心能力拿不到。这里把 suffix 折叠进提示词只作为<b>弱约束</b>， 效果不等价于原生 FIM。
 *
 * <p><b>一个实测踩过的坑（务必保留）</b>：suffix 提示必须放在 {@code system} 消息里， <b>不能放在 {@code user} 消息里</b>。放在 user
 * 时，实测（qwen3-coder-flash，2026-11-25） 长前缀 + 长后缀会稳定返回 {@code finish_reason=stop} 且 {@code content
 * 为空}—— HTTP 200、无异常，只是模型认为"没什么要补的"。改为 system 承载后稳定有输出。 这类静默空返回不会报错，只能靠"内容非空"断言兜住。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class DashScopePartialWire {

    private DashScopePartialWire() {}

    /** 默认引导语：明确要求"只输出代码、不要解释"，实测能显著降低多吐解释的概率。 */
    static final String DEFAULT_INSTRUCTION = "请补全这段代码中间缺失的部分，只输出需要补全的代码，不要输出解释或 Markdown 代码块。";

    /**
     * 构造 Chat Completions 请求体。
     *
     * <p>消息顺序：{@code system}（可选，承载 suffix 弱约束）→ {@code user}（指令）→ {@code assistant}（前缀 + {@code
     * partial=true}，必须是最后一条）。
     *
     * @param suffixHintEnabled 是否把 suffix 折叠进 system 提示词作为弱约束
     */
    static Map<String, Object> buildPayload(
            String modelName,
            FimRequest request,
            DefaultFimRequestParameters parameters,
            String instruction,
            boolean suffixHintEnabled,
            boolean streaming,
            boolean includeUsage) {

        List<Object> messages = new ArrayList<Object>();

        // suffix 只能放 system：放 user 会导致模型返回空内容（见类注释）。
        if (suffixHintEnabled && request.suffix() != null && !request.suffix().isEmpty()) {
            messages.add(systemMessage(request.suffix()));
        }
        messages.add(userMessage(instruction));
        // 关键：最后一条必须是 assistant + partial=true，模型才会把 content 当作前缀续写
        messages.add(assistantPartialMessage(request.prefix()));

        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("messages", messages);

        if (parameters.maxTokens() != null) {
            payload.put("max_tokens", parameters.maxTokens());
        }
        if (parameters.temperature() != null) {
            payload.put("temperature", parameters.temperature());
        }
        if (parameters.topP() != null) {
            payload.put("top_p", parameters.topP());
        }
        if (parameters.stopSequences() != null && !parameters.stopSequences().isEmpty()) {
            payload.put("stop", parameters.stopSequences());
        }
        payload.put("stream", Boolean.valueOf(streaming));
        if (streaming && includeUsage) {
            LinkedHashMap<String, Object> streamOptions = new LinkedHashMap<String, Object>();
            streamOptions.put("include_usage", Boolean.TRUE);
            payload.put("stream_options", streamOptions);
        }
        return payload;
    }

    /**
     * system 消息：承载"光标之后已存在的代码"。
     *
     * <p>刻意不用 ``` 包裹，避免与 {@code stop} 里的 ``` 混淆；纯文本即可让模型理解。
     */
    private static Map<String, Object> systemMessage(String suffix) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "system");
        item.put("content", "你是代码补全助手。光标之后已存在如下代码，补全时不要重复生成它：\n" + suffix);
        return item;
    }

    private static Map<String, Object> userMessage(String instruction) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "user");
        item.put("content", instruction);
        return item;
    }

    private static Map<String, Object> assistantPartialMessage(String prefix) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "assistant");
        item.put("content", prefix == null ? "" : prefix);
        item.put("partial", Boolean.TRUE);
        return item;
    }

    /** 解析非流式响应：文本在 {@code choices[0].message.content}。 */
    static FimResponse parseResponse(String body, String fallbackModel, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        List<Object> choices = Json.array(root.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new ModelException("DashScope response has no choices", null, body);
        }
        Map<String, Object> choice = Json.object(choices.get(0));
        Map<String, Object> message = choice == null ? null : Json.object(choice.get("message"));
        String text = message == null ? "" : Json.string(message.get("content"));
        String model = Json.string(root.get("model"));

        return FimResponse.builder()
                .completion(text == null ? "" : text)
                .finishReason(mapFinishReason(choice == null ? null : choice.get("finish_reason")))
                .usage(parseUsage(Json.object(root.get("usage"))))
                .model(model == null ? fallbackModel : model)
                .latencyMs(latencyMs)
                .metadata("systemFingerprint", root.get("system_fingerprint"))
                .build();
    }

    /** 归一化 finish_reason；百炼沿用 OpenAI 取值。 */
    static FimFinishReason mapFinishReason(Object value) {
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
        return FimFinishReason.OTHER;
    }

    /** 归一化 usage；百炼用 {@code prompt_tokens_details.cached_tokens}。 */
    static FimUsage parseUsage(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }
        long prompt = Json.longValue(usage.get("prompt_tokens"), 0L);
        long completion = Json.longValue(usage.get("completion_tokens"), 0L);
        long total = Json.longValue(usage.get("total_tokens"), prompt + completion);
        long cached = 0L;
        Map<String, Object> details = Json.object(usage.get("prompt_tokens_details"));
        if (details != null) {
            cached = Json.longValue(details.get("cached_tokens"), 0L);
        }
        return new FimUsage(prompt, completion, total, cached);
    }

    /** 流式增量在 {@code choices[0].delta.content}。 */
    static String deltaText(Object choicesValue) {
        List<Object> choices = Json.array(choicesValue);
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Map<String, Object> choice = Json.object(choices.get(0));
        if (choice == null) {
            return null;
        }
        Map<String, Object> delta = Json.object(choice.get("delta"));
        if (delta == null) {
            return null;
        }
        return Json.string(delta.get("content"));
    }

    /** 默认 stop：阻止模型续写下一个成员，并兜住 Markdown 围栏。 */
    static List<String> defaultStops() {
        return Arrays.asList("\n\n    public ", "\n\n    private ", "```");
    }

    static String trimTrailingSlash(String url) {
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
