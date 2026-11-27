package cloud.changlu.agentforge.model.dashscope.fim;

import cloud.changlu.agentforge.model.cancel.CancellationHandle;
import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
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
 * 百炼（DashScope）Partial Mode 的流式 FIM 实现（SSE）。
 *
 * <p>与 {@link DashScopeFimModel} 的差异仅在传输方式，请求体完全共享 {@link DashScopePartialWire#buildPayload}。
 *
 * <p>流式解析要点：增量在 {@code choices[0].delta.content}，不是 Completions 的 {@code choices[0].text}；{@code
 * usage} 出现在末块（含 {@code stream_options.include_usage}）。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class DashScopeStreamingFimModel implements StreamingFimModel {

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
    private final boolean includeUsage;

    private DashScopeStreamingFimModel(Builder builder) {
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
        this.includeUsage = builder.includeUsage;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 百炼流式最简构造：只需要 Key。 */
    public static DashScopeStreamingFimModel bailian(String apiKey) {
        return builder().apiKey(apiKey).build();
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
                        .url(baseUrl + "/chat/completions")
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .headers(customHeaders)
                        .body(
                                Json.stringify(
                                        DashScopePartialWire.buildPayload(
                                                modelName,
                                                request,
                                                parameters,
                                                instruction,
                                                suffixHintEnabled,
                                                true,
                                                includeUsage)))
                        .connectTimeoutMillis(connectTimeoutMillis)
                        .readTimeoutMillis(readTimeoutMillis);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        DashScopeStreamState state = new DashScopeStreamState(handler, modelName);
        try {
            return httpTransport.executeStreamingCancellable(requestBuilder.build(), state);
        } catch (Throwable error) {
            state.onError(error);
            return CancellationHandle.NONE;
        }
    }

    /** SSE 聚合状态机；包私有便于单测。 */
    static final class DashScopeStreamState implements StreamingHttpResponseHandler {

        private final StreamingFimResponseHandler handler;
        private final String modelName;
        private final StringBuilder buffer = new StringBuilder();
        private final long startTime = System.currentTimeMillis();
        private boolean firstChunkDelivered;
        private String finishReason;
        private FimUsage usage;
        private boolean finished;

        DashScopeStreamState(StreamingFimResponseHandler handler, String modelName) {
            this.handler = handler;
            this.modelName = modelName;
        }

        @Override
        public void onOpen(int statusCode, Map<String, List<String>> headers) {
            if (statusCode < 200 || statusCode >= 300) {
                finished = true;
                handler.onError(
                        new ModelException(
                                "DashScope stream failed with HTTP " + statusCode,
                                statusCode,
                                null));
            }
        }

        @Override
        public void onLine(String line) {
            if (finished || line == null || line.isEmpty() || !line.startsWith("data:")) {
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
                // 单个坏块不中断整个流
                return;
            }
            String delta = DashScopePartialWire.deltaText(root.get("choices"));
            if (delta != null && !delta.isEmpty()) {
                buffer.append(delta);
                FimChunk chunk = new FimChunk(delta);
                if (!firstChunkDelivered) {
                    firstChunkDelivered = true;
                    handler.onFirstChunk(chunk);
                }
                handler.onPartialCompletion(chunk);
            }
            List<Object> choices = Json.array(root.get("choices"));
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = Json.object(choices.get(0));
                if (choice != null && choice.get("finish_reason") != null) {
                    finishReason = Json.string(choice.get("finish_reason"));
                }
            }
            if (root.get("usage") != null) {
                usage = DashScopePartialWire.parseUsage(Json.object(root.get("usage")));
            }
        }

        @Override
        public void onComplete() {
            if (finished) {
                return;
            }
            finished = true;
            handler.onComplete(
                    FimResponse.builder()
                            .completion(buffer.toString())
                            .finishReason(DashScopePartialWire.mapFinishReason(finishReason))
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
    }

    public static final class Builder {
        private String baseUrl = DashScopeFimModel.DEFAULT_BASE_URL;
        private String apiKey;
        private String modelName = DashScopeFimModel.DEFAULT_MODEL;
        private Integer maxTokens = 128;
        private Double temperature = 0.0d;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();
        private final Map<String, String> customHeaders = new LinkedHashMap<String, String>();
        private HttpTransport httpTransport = new JdkHttpTransport();
        private int connectTimeoutMillis = 2_000;
        private int readTimeoutMillis = 5_000;
        private String instruction;

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

        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        public Builder suffixHintEnabled(boolean suffixHintEnabled) {
            this.suffixHintEnabled = suffixHintEnabled;
            return this;
        }

        public Builder includeUsage(boolean includeUsage) {
            this.includeUsage = includeUsage;
            return this;
        }

        public DashScopeStreamingFimModel build() {
            if (modelName == null || modelName.trim().isEmpty()) {
                throw new IllegalStateException("modelName must not be blank");
            }
            return new DashScopeStreamingFimModel(this);
        }
    }
}
