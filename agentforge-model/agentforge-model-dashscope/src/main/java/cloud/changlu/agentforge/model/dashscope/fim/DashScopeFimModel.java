package cloud.changlu.agentforge.model.dashscope.fim;

import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.FimCall;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.http.HttpCall;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）Partial Mode 的 FIM 实现。
 *
 * <p><b>与 {@code OpenAiFimModel} 的关键差异</b>：
 *
 * <ul>
 *   <li>端点是 {@code /chat/completions}，不是 {@code /completions}；
 *   <li>请求体是 {@code messages}，不是 {@code prompt} + {@code suffix}；
 *   <li>前缀通过"最后一条 {@code role=assistant} + {@code partial=true}"表达；
 *   <li>文本在 {@code choices[0].message.content}（流式在 {@code delta.content}）。
 * </ul>
 *
 * <p><b>能力边界</b>：Partial Mode 只支持前缀续写，<b>没有原生 suffix</b>。 本实现会把 suffix 折叠进 user 提示词作为弱约束，但效果不等价于
 * DeepSeek 原生 FIM —— 模型本质上仍不知道"光标之后确切是什么"。若需要真正的 FIM，请用 {@code OpenAiFimModel}（DeepSeek Beta /
 * Completions + suffix）。
 *
 * <p>官方 Partial Mode 支持模型清单包含 Qwen3-Coder / Qwen2.5-Coder / Qwen-Coder 系列； 注意 {@code
 * deepseek-v4.1-flash} <b>不在</b>该清单内。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class DashScopeFimModel implements FimModel {

    /** 北京地域旧域名，官方仍在维护但建议迁移到业务空间专属域名。 */
    public static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** Partial Mode 支持列表内的默认模型。 */
    public static final String DEFAULT_MODEL = "qwen3-coder-flash";

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final DefaultFimRequestParameters defaultParameters;
    private final Map<String, String> customHeaders;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String instruction;
    private final boolean suffixHintEnabled;

    private DashScopeFimModel(Builder builder) {
        this.baseUrl = DashScopePartialWire.trimTrailingSlash(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.defaultParameters =
                DefaultFimRequestParameters.builder()
                        .maxTokens(builder.maxTokens)
                        .temperature(builder.temperature)
                        .topP(builder.topP)
                        .stopSequences(
                                builder.stopSequences == null
                                        ? DashScopePartialWire.defaultStops()
                                        : builder.stopSequences)
                        .customParameters(builder.customParameters)
                        .build();
        this.customHeaders =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, String>(builder.customHeaders));
        this.httpTransport = builder.httpTransport;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.instruction =
                builder.instruction == null || builder.instruction.trim().isEmpty()
                        ? DashScopePartialWire.DEFAULT_INSTRUCTION
                        : builder.instruction.trim();
        this.suffixHintEnabled = builder.suffixHintEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 百炼最简构造：只需要 Key。 */
    public static DashScopeFimModel bailian(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    @Override
    public FimResponse complete(FimRequest request) {
        return newCall(request).execute();
    }

    @Override
    public FimCall newCall(FimRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final DefaultFimRequestParameters parameters =
                DefaultFimRequestParameters.merge(defaultParameters, request.parameters());
        final HttpRequest httpRequest = buildHttpRequest(request, parameters);
        final HttpCall httpCall = httpTransport.newCall(httpRequest);

        return new FimCall() {
            @Override
            public FimResponse execute() {
                long start = System.currentTimeMillis();
                try {
                    HttpResponse response = httpCall.execute();
                    if (!response.isSuccessful()) {
                        throw new ModelException(
                                "DashScope request failed with HTTP " + response.statusCode(),
                                response.statusCode(),
                                response.body());
                    }
                    return DashScopePartialWire.parseResponse(
                            response.body(), modelName, System.currentTimeMillis() - start);
                } catch (IOException e) {
                    throw new ModelException("DashScope request failed", e);
                }
            }

            @Override
            public boolean cancel() {
                return httpCall.cancel();
            }

            @Override
            public boolean isCancelled() {
                return httpCall.isCancelled();
            }
        };
    }

    private HttpRequest buildHttpRequest(
            FimRequest request, DefaultFimRequestParameters parameters) {
        HttpRequest.Builder builder =
                HttpRequest.builder()
                        .url(baseUrl + "/chat/completions")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .headers(customHeaders)
                        .body(
                                Json.stringify(
                                        DashScopePartialWire.buildPayload(
                                                modelName,
                                                request,
                                                parameters,
                                                instruction,
                                                suffixHintEnabled,
                                                false,
                                                false)))
                        .connectTimeoutMillis(connectTimeoutMillis)
                        .readTimeoutMillis(readTimeoutMillis);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return builder.build();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String modelName = DEFAULT_MODEL;
        private Integer maxTokens = 128;
        private Double temperature = 0.0d;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();
        private final Map<String, String> customHeaders = new LinkedHashMap<String, String>();
        private HttpTransport httpTransport = new JdkHttpTransport();
        private int connectTimeoutMillis = 2_000;
        private int readTimeoutMillis = 3_000;
        private String instruction;

        /** 默认开启：把 suffix 折叠进提示词做弱约束。关掉则是纯前缀续写。 */
        /**
         * 是否把 suffix 折叠进 system 提示词做弱约束。
         *
         * <p><b>默认关闭</b>：实测（qwen3-coder-flash，2026-11-25）在长前缀 + 长后缀的真实业务代码上， 只要把 suffix
         * 塞进提示词（system 或 user 都一样），模型会稳定返回 {@code finish_reason=stop} 且 {@code content 为空}——HTTP
         * 200、无异常，只是"认为没什么要补"。 纯前缀续写反而稳定有输出。
         *
         * <p>结论：百炼 Partial Mode 下"suffix 提示"弊大于利，默认不开。确需尝试可打开， 但上层必须对空补全做兜底。
         */
        private boolean suffixHintEnabled = false;

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences;
            return this;
        }

        public Builder customParameter(String key, Object value) {
            if (key != null && value != null) {
                this.customParameters.put(key, value);
            }
            return this;
        }

        public Builder customHeader(String name, String value) {
            if (name != null && value != null) {
                this.customHeaders.put(name, value);
            }
            return this;
        }

        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        /** 自定义引导语；留空使用默认"只输出代码"的指令。 */
        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        /** 是否把 suffix 折叠进提示词做弱约束（默认 true）。 */
        public Builder suffixHintEnabled(boolean suffixHintEnabled) {
            this.suffixHintEnabled = suffixHintEnabled;
            return this;
        }

        public DashScopeFimModel build() {
            if (modelName == null || modelName.trim().isEmpty()) {
                throw new IllegalStateException("modelName must not be blank");
            }
            return new DashScopeFimModel(this);
        }
    }
}
