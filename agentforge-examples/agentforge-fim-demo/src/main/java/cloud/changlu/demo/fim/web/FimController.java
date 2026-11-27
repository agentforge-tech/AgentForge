package cloud.changlu.demo.fim.web;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;
import cloud.changlu.demo.fim.config.FimProperties;
import cloud.changlu.demo.fim.dto.CompleteRequest;
import cloud.changlu.demo.fim.service.FimRequestAssembler;
import cloud.changlu.demo.fim.service.TokenProbe;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补全接口。
 *
 * <p>四个端点各司其职：
 *
 * <ul>
 *   <li>{@code POST /api/fim/complete}：非流式，便于断言与复制结果；
 *   <li>{@code GET /api/fim/stream}：SSE 流式，前端逐块渲染；
 *   <li>{@code GET /api/fim/probe}：Token 形态探测，接入前先用它确认配置；
 *   <li>{@code GET /api/fim/info}：当前生效配置，便于排查。
 * </ul>
 *
 * <p>本类只做"HTTP ↔ 业务"的协议适配，请求组装与截断策略在 {@link FimRequestAssembler}。
 *
 * @author changlu
 * @date 2026/11/26
 */
@RestController
@RequestMapping("/api/fim")
public class FimController {

    private final FimModel fimModel;
    private final StreamingFimModel streamingFimModel;
    private final FimProperties properties;
    private final FimRequestAssembler assembler;
    private final TokenProbe tokenProbe;

    /** 流式请求在独立线程执行，避免长期占用 Tomcat 工作线程。 */
    private final ExecutorService streamExecutor =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "fim-stream");
                        thread.setDaemon(true);
                        return thread;
                    });

    public FimController(
            FimModel fimModel,
            StreamingFimModel streamingFimModel,
            FimProperties properties,
            FimRequestAssembler assembler,
            TokenProbe tokenProbe) {
        this.fimModel = fimModel;
        this.streamingFimModel = streamingFimModel;
        this.properties = properties;
        this.assembler = assembler;
        this.tokenProbe = tokenProbe;
    }

    /** 非流式补全。 */
    @PostMapping("/complete")
    public Map<String, Object> complete(@RequestBody CompleteRequest request) {
        FimResponse response = fimModel.complete(assembler.assemble(request));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("completion", response.completion());
        result.put("empty", Boolean.valueOf(response.isEmpty()));
        result.put("finishReason", String.valueOf(response.finishReason()));
        result.put("model", response.model());
        result.put("latencyMs", Long.valueOf(response.latencyMs()));
        result.put("usage", usageMap(response));
        return result;
    }

    /**
     * 流式补全（SSE）。
     *
     * <p>事件类型：{@code chunk}（增量）、{@code done}（汇总）、{@code error}。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam String prefix,
            @RequestParam(required = false, defaultValue = "") String suffix,
            @RequestParam(required = false) String language) {

        SseEmitter emitter = new SseEmitter(Long.valueOf(properties.getTimeoutSeconds()) * 1000L);
        AtomicBoolean closed = new AtomicBoolean(false);

        CompleteRequest payload = new CompleteRequest();
        payload.setPrefix(prefix);
        payload.setSuffix(suffix);
        payload.setLanguage(language);
        FimRequest fimRequest = assembler.assemble(payload);

        long start = System.currentTimeMillis();
        AtomicLong firstChunkAt = new AtomicLong(0L);

        streamExecutor.execute(() -> runStream(emitter, closed, fimRequest, start, firstChunkAt));

        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(throwable -> closed.set(true));
        return emitter;
    }

    /** 当前生效配置。 */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("enabled", Boolean.valueOf(properties.isEnabled()));
        result.put("providerCode", Integer.valueOf(properties.getProviderCode()));
        result.put("baseUrl", properties.getBaseUrl());
        result.put("modelName", properties.getModelName());
        result.put("template", properties.getTemplate());
        result.put("temperature", Double.valueOf(properties.getTemperature()));
        result.put("maxTokens", Integer.valueOf(properties.getMaxTokens()));
        result.put("timeoutSeconds", Integer.valueOf(properties.getTimeoutSeconds()));
        result.put("streaming", Boolean.valueOf(properties.isStreaming()));
        result.put("stop", properties.getStop());
        return result;
    }

    /**
     * Token 形态探测。
     *
     * <p>形态配错不会报错，只会让补全返回空字符串，因此这是接入前的必做一步。
     */
    @GetMapping("/probe")
    public Map<String, Object> probe() {
        return tokenProbe.probe(properties.getBaseUrl(), properties.getModelName());
    }

    // ==================== 内部实现 ====================

    private void runStream(
            SseEmitter emitter,
            AtomicBoolean closed,
            FimRequest fimRequest,
            long start,
            AtomicLong firstChunkAt) {
        try {
            streamingFimModel.complete(
                    fimRequest,
                    new StreamingFimResponseHandler() {
                        @Override
                        public void onFirstChunk(FimChunk chunk) {
                            firstChunkAt.set(System.currentTimeMillis());
                        }

                        @Override
                        public void onPartialCompletion(FimChunk chunk) {
                            Map<String, Object> data = new LinkedHashMap<String, Object>();
                            data.put("delta", chunk.delta());
                            send(emitter, closed, "chunk", data);
                        }

                        @Override
                        public void onComplete(FimResponse response) {
                            long first = firstChunkAt.get();
                            Map<String, Object> data = new LinkedHashMap<String, Object>();
                            data.put("completion", response.completion());
                            data.put("finishReason", String.valueOf(response.finishReason()));
                            data.put("latencyMs", Long.valueOf(response.latencyMs()));
                            data.put("ttftMs", Long.valueOf(first == 0L ? -1L : first - start));
                            data.put("wallMs", Long.valueOf(System.currentTimeMillis() - start));
                            data.put("usage", usageMap(response));
                            send(emitter, closed, "done", data);
                            complete(emitter, closed);
                        }

                        @Override
                        public void onError(Throwable error) {
                            Map<String, Object> data = new LinkedHashMap<String, Object>();
                            data.put("message", rootMessage(error));
                            send(emitter, closed, "error", data);
                            complete(emitter, closed);
                        }
                    });
        } catch (RuntimeException e) {
            // 装配期异常（如模板名非法）不会走 handler，需要单独兜底
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("message", rootMessage(e));
            send(emitter, closed, "error", data);
            complete(emitter, closed);
        }
    }

    /** 统一的 SSE 下发；客户端断开时置位 closed，后续调用自动短路。 */
    private void send(
            SseEmitter emitter, AtomicBoolean closed, String event, Map<String, Object> data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            // 客户端已断开：停止推送，但不是模型错误
            closed.set(true);
        }
    }

    private void complete(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        emitter.complete();
    }

    private static Map<String, Object> usageMap(FimResponse response) {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        if (response.usage() == null) {
            usage.put("available", Boolean.FALSE);
            return usage;
        }
        usage.put("available", Boolean.TRUE);
        usage.put("inputTokens", Long.valueOf(response.usage().inputTokens()));
        usage.put("outputTokens", Long.valueOf(response.usage().outputTokens()));
        usage.put("totalTokens", Long.valueOf(response.usage().totalTokens()));
        usage.put("cachedInputTokens", Long.valueOf(response.usage().cachedInputTokens()));
        return usage;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
