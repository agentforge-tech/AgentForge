package cloud.changlu.agentforge.model.openai.fim;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.http.StreamingHttpResponseHandler;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Completions 形状的流式 FIM 实现（SSE）。
 *
 * <p>解析要点（DeepSeek 官方文档明确说明）：
 *
 * <ul>
 *   <li>增量在 {@code choices[0].text}，流式与非流式响应对象结构相同；
 *   <li>{@code usage} <b>不会</b>单独下发一个块，而是附加在 {@code data: [DONE]} 之前的 最后一个内容块上——该块 {@code text} 为空且
 *       {@code finish_reason} 非 null；
 *   <li>因此必须以"最后一个块"为准收集用量，否则拿不到计费数据。
 * </ul>
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class OpenAiStreamingFimModel implements StreamingFimModel {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final DefaultFimRequestParameters defaultParameters;
    private final Map<String, String> customHeaders;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final boolean disableThinking;
    private final boolean includeUsage;

    private OpenAiStreamingFimModel(Builder builder) {
        this.baseUrl = OpenAiFimWire.trimTrailingSlash(builder.baseUrl);
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
        this.includeUsage = builder.includeUsage;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** DeepSeek 流式 FIM 的最简构造。 */
    public static OpenAiStreamingFimModel deepSeek(String apiKey) {
        return builder().baseUrl(OpenAiFimModel.DEEPSEEK_BETA_BASE_URL).apiKey(apiKey).build();
    }

    @Override
    public void complete(FimRequest request, StreamingFimResponseHandler handler) {
        completeCancellable(request, handler);
    }

    @Override
    public CancellationHandle completeCancellable(
            FimRequest request, StreamingFimResponseHandler handler) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        DefaultFimRequestParameters parameters =
                DefaultFimRequestParameters.merge(defaultParameters, request.parameters());

        HttpRequest.Builder requestBuilder =
                HttpRequest.builder()
                        .url(baseUrl + "/completions")
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .headers(customHeaders)
                        .body(Json.stringify(buildPayload(request, parameters)))
                        .connectTimeoutMillis(connectTimeoutMillis)
                        .readTimeoutMillis(readTimeoutMillis);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        FimStreamState state = new FimStreamState(handler, modelName);
        try {
            return httpTransport.executeStreamingCancellable(requestBuilder.build(), state);
        } catch (Throwable error) {
            state.onError(error);
            return CancellationHandle.NONE;
        }
    }

    private Map<String, Object> buildPayload(
            FimRequest request, DefaultFimRequestParameters parameters) {
        return OpenAiFimWire.buildPayload(
                modelName, request, parameters, true, includeUsage, disableThinking);
    }

    /**
     * SSE 聚合状态机。
     *
     * <p>包私有，便于单测直接灌行数据验证解析边界（空 text、usage-only 末块、[DONE]）。
     */
    static final class FimStreamState implements StreamingHttpResponseHandler {

        private final StreamingFimResponseHandler handler;
        private final String modelName;
        private final StringBuilder buffer = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private boolean firstChunkDelivered;
        private String finishReason;
        private FimUsage usage;
        private boolean finished;

        FimStreamState(StreamingFimResponseHandler handler, String modelName) {
            this.handler = handler;
            this.modelName = modelName;
        }

        @Override
        public void onOpen(int statusCode, Map<String, List<String>> headers) {
            if (statusCode < 200 || statusCode >= 300) {
                finished = true;
                handler.onError(
                        new ModelException(
                                "FIM stream failed with HTTP " + statusCode, statusCode, null));
            }
        }

        @Override
        public void onLine(String line) {
            if (finished || line == null || line.isEmpty()) {
                return;
            }
            if (!line.startsWith("data:")) {
                return;
            }
            String data = line.substring("data:".length()).trim();
            if ("[DONE]".equals(data)) {
                return;
            }
            consume(data);
        }

        void consume(String json) {
            final Map<String, Object> root;
            try {
                root = Json.parseObject(json);
            } catch (RuntimeException parseError) {
                // 单个坏块不应中断整个流：记录后可继续消费后续片段。
                return;
            }
            List<Object> choices = Json.array(root.get("choices"));
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = Json.object(choices.get(0));
                if (choice != null) {
                    String text = Json.string(choice.get("text"));
                    if (text != null && !text.isEmpty()) {
                        buffer.append(text);
                        FimChunk chunk = new FimChunk(text);
                        if (!firstChunkDelivered) {
                            firstChunkDelivered = true;
                            handler.onFirstChunk(chunk);
                        }
                        handler.onPartialCompletion(chunk);
                    }
                    if (choice.get("finish_reason") != null) {
                        finishReason = Json.string(choice.get("finish_reason"));
                    }
                }
            }
            // 统计信息只出现在最后一个内容块上，不在独立的 usage-only 块上。
            if (root.get("usage") != null) {
                usage = OpenAiFimWire.parseUsage(Json.object(root.get("usage")));
            }
        }

        @Override
        public void onComplete() {
            if (finished) {
                return;
            }
            finished = true;
            FimFinishReason reason = OpenAiFimWire.mapFinishReason(finishReason);
            handler.onComplete(
                    FimResponse.builder()
                            .completion(buffer.toString())
                            .finishReason(reason)
                            .usage(usage)
                            .model(modelName)
                            .latencyMs(System.currentTimeMillis() - startTime)
                            .build());
        }

        @Override
        public void onError(Throwable error) {
            if (finished) {
                return;
            }
            finished = true;
            handler.onError(error);
        }

        /** 已接收的字符数，供测试与内部诊断使用。 */
        int receivedLength() {
            return buffer.length();
        }
    }

    public static final class Builder {
        private String baseUrl = OpenAiFimModel.DEEPSEEK_BETA_BASE_URL;
        private String apiKey;
        private String modelName = OpenAiFimModel.DEEPSEEK_DEFAULT_MODEL;
        private Integer maxTokens = 128;
        private Double temperature = 0.0d;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();
        private final Map<String, String> customHeaders = new LinkedHashMap<String, String>();
        private HttpTransport httpTransport = new JdkHttpTransport();
        private int connectTimeoutMillis = 2_000;
        private int readTimeoutMillis = 3_000;
        private boolean disableThinking = true;
        private boolean includeUsage = true;

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

        public Builder disableThinking(boolean disableThinking) {
            this.disableThinking = disableThinking;
            return this;
        }

        public Builder includeUsage(boolean includeUsage) {
            this.includeUsage = includeUsage;
            return this;
        }

        public OpenAiStreamingFimModel build() {
            if (modelName == null || modelName.trim().isEmpty()) {
                throw new IllegalStateException("modelName must not be blank");
            }
            return new OpenAiStreamingFimModel(this);
        }
    }
}
