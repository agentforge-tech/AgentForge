package cloud.changlu.demo.fim.service;

import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIM Token 形态探测。
 *
 * <p><b>为什么需要它</b>：特殊 Token 是"注册进 tokenizer"才算数，不是模型名决定的。 同一个 CodeQwen1.5-7B，HuggingFace
 * 官方权重注册的是带竖线 {@code <|fim_prefix|>}， 而某些容器化部署的权重只注册无竖线 {@code <fim_prefix>}。
 *
 * <p>配错的后果极其隐蔽：<b>HTTP 200、无异常、补全为空字符串</b>，只表现为"模型好像不太行"。
 *
 * <p><b>判定原理</b>：真正的 special token 只占 1 个 token。把三个标记裸拼在一起发出去， 看 {@code usage.prompt_tokens}：
 *
 * <ul>
 *   <li>等于 3 → 三个标记都是特殊 Token，该形态可用；
 *   <li>远大于 3（通常 20+）→ 被当普通文本切碎，该形态不可用。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/26
 */
@Component
public class TokenProbe {

    /** 每种形态的裸标记组合。 */
    private static final String[][] PROBES = {
        {"qwen-no-pipe", "<fim_prefix><fim_suffix><fim_middle>"},
        {"qwen", "<|fim_prefix|><|fim_suffix|><|fim_middle|>"},
        {"starcoder2", "<fim_prefix><fim_suffix><fim_middle>"},
        {"code-llama", "<PRE> <SUF> <MID>"},
        {"codestral", "[SUFFIX][PREFIX]"}
    };

    private final HttpTransport httpTransport = new JdkHttpTransport();

    /**
     * 探测各形态的实际 token 数。
     *
     * @return 包含每种形态结果、推荐模板与原始 tokenizer 提示的 Map
     */
    public Map<String, Object> probe(String baseUrl, String modelName) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        String recommended = null;
        int bestCount = Integer.MAX_VALUE;

        for (String[] entry : PROBES) {
            String name = entry[0];
            String prompt = entry[1];

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("template", name);
            item.put("prompt", prompt);

            Integer promptTokens = countPromptTokens(baseUrl, modelName, prompt);
            item.put("promptTokens", promptTokens);
            // 3 个标记若都是特殊 Token，合计恰为 3
            item.put("isSpecialToken", Boolean.valueOf(promptTokens != null && promptTokens == 3));
            candidates.add(item);

            if (promptTokens != null && promptTokens < bestCount) {
                bestCount = promptTokens;
                // 只有真的是 3 才推荐；否则说明该部署可能根本没注册 FIM Token
                recommended = promptTokens == 3 ? name : recommended;
            }
        }

        result.put("baseUrl", baseUrl);
        result.put("modelName", modelName);
        result.put("expected", "promptTokens == 3 表示三个标记都是特殊 Token");
        result.put("candidates", candidates);
        result.put("recommendedTemplate", recommended == null ? "native-suffix" : recommended);
        result.put(
                "hint",
                recommended == null
                        ? "未发现标准 FIM 特殊 Token：该部署可能未注册，建议先用 native-suffix 观察实际行为，"
                                + "或向运维索取 tokenizer_config.json 的 additional_special_tokens。"
                        : "推荐模板 " + recommended + "，把 fim.template 配成该值即可。");
        return result;
    }

    /** 发一次 max_tokens=1 的极小的请求，只为拿 prompt_tokens。 */
    private Integer countPromptTokens(String baseUrl, String modelName, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("prompt", prompt);
        payload.put("max_tokens", Integer.valueOf(1));
        payload.put("temperature", Double.valueOf(0.0d));
        payload.put("stream", Boolean.FALSE);

        HttpRequest request =
                HttpRequest.builder()
                        .url(trimTrailingSlash(baseUrl) + "/completions")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .body(Json.stringify(payload))
                        .connectTimeoutMillis(10_000)
                        .readTimeoutMillis(30_000)
                        .build();

        try {
            HttpResponse response = httpTransport.execute(request);
            if (!response.isSuccessful()) {
                return null;
            }
            Map<String, Object> root = Json.parseObject(response.body());
            Map<String, Object> usage = Json.object(root.get("usage"));
            if (usage == null) {
                return null;
            }
            long value = Json.longValue(usage.get("prompt_tokens"), -1L);
            return value < 0L ? null : Integer.valueOf((int) value);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String url) {
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
