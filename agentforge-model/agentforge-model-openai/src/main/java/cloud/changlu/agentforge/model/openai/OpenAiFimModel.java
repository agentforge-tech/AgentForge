package cloud.changlu.agentforge.model.openai;

import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.FimCall;
import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
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
 * OpenAI Completions 形状的 FIM 实现。
 *
 * <p>DeepSeek FIM（{@code https://api.deepseek.com/beta} + {@code POST /completions}）与任何
 * OpenAI-compatible completions 服务都能直接复用本类，不需要单独的 DeepSeek 适配模块。
 *
 * <p>与 {@link OpenAiChatModel} 的关键差异：
 *
 * <ul>
 *   <li>端点是 {@code /completions}，不是 {@code /chat/completions}；
 *   <li>请求体用 {@code prompt} + {@code suffix}，不是 {@code messages}；
 *   <li>响应取 {@code choices[0].text}，不是 {@code choices[0].message.content}；
 *   <li>{@code finish_reason} 需要额外处理 {@code insufficient_system_resource} / {@code aborted}。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class OpenAiFimModel implements FimModel {

    /** DeepSeek FIM 的官方 base，必须带 {@code /beta}。 */
    public static final String DEEPSEEK_BETA_BASE_URL = "https://api.deepseek.com/beta";

    /** DeepSeek 默认用于 FIM 的模型。 */
    public static final String DEEPSEEK_DEFAULT_MODEL = "deepseek-flash";

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final DefaultFimRequestParameters defaultParameters;
    private final Map<String, String> customHeaders;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final boolean disableThinking;

    private OpenAiFimModel(Builder builder) {
        this.baseUrl = trimTrailingSlash(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.defaultParameters =
                DefaultFimRequestParameters.builder()
                        .maxTokens(builder.maxTokens)
                        .temperature(builder.temperature)
                        .topP(builder.topP)
                        .stopSequences(builder.stopSequences)
                        .customParameters(builder.customParameters)
                        .build();
        this.customHeaders =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, String>(builder.customHeaders));
        this.httpTransport = builder.httpTransport;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.disableThinking = builder.disableThinking;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** DeepSeek FIM 的最简构造：base、key、模型名，其余走补全场景默认值。 */
    public static OpenAiFimModel deepSeek(String apiKey) {
        return builder().baseUrl(DEEPSEEK_BETA_BASE_URL).apiKey(apiKey).build();
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
                                "FIM request failed with HTTP " + response.statusCode(),
                                response.statusCode(),
                                response.body());
                    }
                    return parseResponse(
                            response.body(), modelName, System.currentTimeMillis() - start);
                } catch (IOException e) {
                    throw new ModelException("FIM request failed", e);
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
                        .url(baseUrl + "/completions")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .headers(customHeaders)
                        .body(Json.stringify(buildPayload(request, parameters)))
                        .connectTimeoutMillis(connectTimeoutMillis)
                        .readTimeoutMillis(readTimeoutMillis);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return builder.build();
    }

    private Map<String, Object> buildPayload(
            FimRequest request, DefaultFimRequestParameters parameters) {
        return OpenAiFimWire.buildPayload(
                modelName, request, parameters, false, false, disableThinking);
    }

    /** 解析 Completions 响应，委托给共享 wire 辅助类。 */
    static FimResponse parseResponse(String body, String fallbackModel, long latencyMs) {
        return OpenAiFimWire.parseResponse(body, fallbackModel, latencyMs);
    }

    /** 归一化 {@code finish_reason}，委托给共享 wire 辅助类。 */
    static FimFinishReason mapFinishReason(Object value) {
        return OpenAiFimWire.mapFinishReason(value);
    }

    /** 归一化 usage，委托给共享 wire 辅助类。 */
    static FimUsage parseUsage(Map<String, Object> usage) {
        return OpenAiFimWire.parseUsage(usage);
    }

    static String trimTrailingSlash(String url) {
        return OpenAiFimWire.trimTrailingSlash(url);
    }

    public static final class Builder {
        private String baseUrl = DEEPSEEK_BETA_BASE_URL;
        private String apiKey;
        private String modelName = DEEPSEEK_DEFAULT_MODEL;
        private Integer maxTokens = 128;
        private Double temperature = 0.0d;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();
        private final Map<String, String> customHeaders = new LinkedHashMap<String, String>();
        private HttpTransport httpTransport = new JdkHttpTransport();
        private int connectTimeoutMillis = 2_000;
        private int readTimeoutMillis = 2_000;
        private boolean disableThinking = true;

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

        /** 补全场景默认 2s：宁可失败也不能拖住编辑器。 */
        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        /** DeepSeek 专用：默认注入 {@code thinking.type=disabled}。 */
        public Builder disableThinking(boolean disableThinking) {
            this.disableThinking = disableThinking;
            return this;
        }

        public OpenAiFimModel build() {
            if (modelName == null || modelName.trim().isEmpty()) {
                throw new IllegalStateException("modelName must not be blank");
            }
            return new OpenAiFimModel(this);
        }
    }
}
