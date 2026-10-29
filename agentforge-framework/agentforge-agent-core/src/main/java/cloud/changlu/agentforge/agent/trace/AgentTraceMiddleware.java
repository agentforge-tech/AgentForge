package cloud.changlu.agentforge.agent.trace;

import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.step.StepResult;
import cloud.changlu.agentforge.agent.step.StopResultState;
import cloud.changlu.agentforge.agent.trace.domain.AgentTraceSnapshot;
import cloud.changlu.agentforge.agent.trace.domain.AgentTraceSnapshotStatus;
import cloud.changlu.agentforge.agent.trace.domain.AgentTraceToolSnapshot;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Best-effort ReAct timing collector. Trace failures never interrupt the Agent execution. */
public class AgentTraceMiddleware implements IAgentMiddleware {

    private static final String RUNTIME_KEY = AgentTraceMiddleware.class.getName() + ".runtime";
    private static final int MAX_TEXT_LENGTH = 4096;

    private final AgentTraceCollector collector;

    public AgentTraceMiddleware(AgentTraceCollector collector) {
        this.collector = collector;
    }

    @Override
    public void onInitComplete(AgentChatContext context) {
        Runtime runtime = new Runtime();
        runtime.memoryId = String.valueOf(context.getMemoryId());
        runtime.question = truncate(context.getQuestion());
        runtime.startedAt = System.currentTimeMillis();
        enrichAttributes(context, runtime.attributes);
        context.putExtension(RUNTIME_KEY, runtime);
    }

    @Override
    public void beforeLoop(int currentStep, AgentChatContext context) {
        Runtime runtime = runtime(context);
        if (runtime == null) return;
        Step step = new Step();
        step.step = currentStep;
        step.startedAt = System.currentTimeMillis();
        runtime.steps.put(currentStep, step);
    }

    @Override
    public ChatRequest beforeModelCall(
            int currentStep, ChatRequest request, AgentChatContext context) {
        Step step = step(context, currentStep);
        if (step != null) step.modelStartedAt = System.currentTimeMillis();
        return request;
    }

    @Override
    public ChatResponse afterModelCall(
            int currentStep, ChatRequest request, ChatResponse response, AgentChatContext context) {
        Step step = step(context, currentStep);
        if (step != null) {
            step.modelFinishedAt = System.currentTimeMillis();
            TokenUsage usage = response == null ? null : response.tokenUsage();
            if (usage != null) {
                step.inputTokens = usage.inputTokens();
                step.outputTokens = usage.outputTokens();
                step.totalTokens = usage.totalTokens();
                step.cachedTokens = usage.cachedTokens();
            }
            if (response != null && response.finishReason() != null) {
                step.attributes.put("finishReason", response.finishReason().toString());
            }
        }
        return response;
    }

    @Override
    public void onModelCallError(
            int currentStep, ChatRequest request, Throwable error, AgentChatContext context) {
        Step step = step(context, currentStep);
        if (step != null) {
            step.modelFinishedAt = System.currentTimeMillis();
            step.error = errorText(error);
        }
    }

    @Override
    public void beforeToolExecution(ToolExecutionRequest request, AgentChatContext context) {
        startTool(currentStep(context), request, context);
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest request, String result, AgentChatContext context) {
        finishTool(currentStep(context), request, result, null, context);
        return result;
    }

    @Override
    public void onToolExecutionError(
            ToolExecutionRequest request, Throwable error, AgentChatContext context) {
        finishTool(currentStep(context), request, null, error, context);
    }

    @Override
    public void beforeConcurrentToolExecution(
            List<ToolExecutionRequest> requests, AgentChatContext context) {
        if (requests == null) return;
        Integer currentStep = currentStep(context);
        for (ToolExecutionRequest request : requests) startTool(currentStep, request, context);
    }

    @Override
    public void onConcurrentToolExecutionComplete(
            ToolExecutionRequest request,
            String result,
            Throwable error,
            AgentChatContext context) {
        finishTool(currentStep(context), request, result, error, context);
    }

    @Override
    public void afterLoop(int currentStep, StepResult result, AgentChatContext context) {
        Step step = step(context, currentStep);
        if (step == null) return;
        step.finishedAt = System.currentTimeMillis();
        publish(snapshot(runtime(context), step, "STEP", false, AgentTraceSnapshotStatus.CONTINUE));
    }

    @Override
    public void onLoopError(int currentStep, Throwable error, AgentChatContext context) {
        Step step = step(context, currentStep);
        if (step == null) return;
        step.finishedAt = System.currentTimeMillis();
        step.error = errorText(error);
        publish(snapshot(runtime(context), step, "STEP", false, AgentTraceSnapshotStatus.ERROR));
    }

    @Override
    public void onStop(int currentStep, StopResult result, AgentChatContext context) {
        AgentTraceSnapshotStatus status = AgentTraceSnapshotStatus.NORMAL;
        if (result != null && result.getStopResultState() == StopResultState.CANCEL) {
            status = AgentTraceSnapshotStatus.CANCEL;
        } else if (result != null && result.getStopResultState() == StopResultState.MAX_STEPS) {
            status = AgentTraceSnapshotStatus.MAX_STEPS;
        }
        finish(context, currentStep, status, null);
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext context) {
        finish(context, currentStep, AgentTraceSnapshotStatus.ERROR, error);
    }

    private void finish(
            AgentChatContext context,
            int currentStep,
            AgentTraceSnapshotStatus status,
            Throwable error) {
        Runtime runtime = runtime(context);
        if (runtime == null || runtime.finished) return;
        runtime.finished = true;
        Step last = runtime.steps.get(currentStep);
        if (last == null && !runtime.steps.isEmpty()) {
            last = new ArrayList<Step>(runtime.steps.values()).get(runtime.steps.size() - 1);
        }
        AgentTraceSnapshot snapshot = aggregate(runtime, last, status);
        snapshot.setError(errorText(error));
        publish(snapshot);
        context.getExtensions().remove(RUNTIME_KEY);
    }

    private AgentTraceSnapshot aggregate(
            Runtime runtime, Step last, AgentTraceSnapshotStatus status) {
        AgentTraceSnapshot snapshot = base(runtime, last == null ? 0 : last.step);
        snapshot.setSnapshotKind("ROUND_FINAL");
        snapshot.setFinished(true);
        snapshot.setStatus(status);
        snapshot.setStartedAt(runtime.startedAt);
        snapshot.setFinishedAt(System.currentTimeMillis());
        snapshot.setDurationMs(snapshot.getFinishedAt() - snapshot.getStartedAt());
        for (Step step : runtime.steps.values()) {
            snapshot.setModelDurationMs(
                    snapshot.getModelDurationMs()
                            + duration(step.modelStartedAt, step.modelFinishedAt));
            snapshot.setToolDurationMs(snapshot.getToolDurationMs() + toolDuration(step));
            snapshot.setInputTokens(snapshot.getInputTokens() + step.inputTokens);
            snapshot.setOutputTokens(snapshot.getOutputTokens() + step.outputTokens);
            snapshot.setTotalTokens(snapshot.getTotalTokens() + step.totalTokens);
            snapshot.setCachedTokens(snapshot.getCachedTokens() + step.cachedTokens);
            snapshot.getTools().addAll(copyTools(step));
        }
        snapshot.getAttributes().put("stepCount", runtime.steps.size());
        return snapshot;
    }

    private AgentTraceSnapshot snapshot(
            Runtime runtime,
            Step step,
            String kind,
            boolean finished,
            AgentTraceSnapshotStatus status) {
        AgentTraceSnapshot snapshot = base(runtime, step.step);
        snapshot.setSnapshotKind(kind);
        snapshot.setFinished(finished);
        snapshot.setStatus(status);
        snapshot.setStartedAt(step.startedAt);
        snapshot.setFinishedAt(step.finishedAt);
        snapshot.setDurationMs(duration(step.startedAt, step.finishedAt));
        snapshot.setModelStartedAt(step.modelStartedAt);
        snapshot.setModelFinishedAt(step.modelFinishedAt);
        snapshot.setModelDurationMs(duration(step.modelStartedAt, step.modelFinishedAt));
        snapshot.setToolDurationMs(toolDuration(step));
        snapshot.setInputTokens(step.inputTokens);
        snapshot.setOutputTokens(step.outputTokens);
        snapshot.setTotalTokens(step.totalTokens);
        snapshot.setCachedTokens(step.cachedTokens);
        snapshot.setError(step.error);
        snapshot.setTools(copyTools(step));
        snapshot.getAttributes().putAll(step.attributes);
        return snapshot;
    }

    private AgentTraceSnapshot base(Runtime runtime, int step) {
        AgentTraceSnapshot snapshot = createSnapshot();
        snapshot.setMemoryId(runtime.memoryId);
        snapshot.setQuestion(runtime.question);
        snapshot.setStep(step);
        snapshot.setAttributes(new LinkedHashMap<String, Object>(runtime.attributes));
        enrichSnapshot(snapshot, snapshot.getAttributes());
        return snapshot;
    }

    /** Creates the protocol object, allowing a business platform to return a snapshot subtype. */
    protected AgentTraceSnapshot createSnapshot() {
        return new AgentTraceSnapshot();
    }

    /** Populates strongly typed fields declared by a business snapshot subtype. */
    protected void enrichSnapshot(
            AgentTraceSnapshot snapshot, Map<String, Object> traceAttributes) {}

    /**
     * Extension hook for a business platform to add serializable metadata to every snapshot.
     * Implementations should only copy observation data and must not change Agent execution state.
     */
    protected void enrichAttributes(
            AgentChatContext context, Map<String, Object> traceAttributes) {}

    private List<AgentTraceToolSnapshot> copyTools(Step step) {
        return new ArrayList<AgentTraceToolSnapshot>(step.tools.values());
    }

    private long toolDuration(Step step) {
        long total = 0;
        for (AgentTraceToolSnapshot tool : step.tools.values()) total += tool.getDurationMs();
        return total;
    }

    private void startTool(
            Integer currentStep, ToolExecutionRequest request, AgentChatContext context) {
        if (currentStep == null || request == null) return;
        Step step = step(context, currentStep);
        if (step == null) return;
        String key = toolKey(request);
        AgentTraceToolSnapshot tool = new AgentTraceToolSnapshot();
        tool.setToolCallId(request.id());
        tool.setToolName(request.name());
        tool.setArguments(truncate(request.arguments()));
        tool.setStartedAt(System.currentTimeMillis());
        tool.setStatus("RUNNING");
        step.tools.put(key, tool);
    }

    private void finishTool(
            Integer currentStep,
            ToolExecutionRequest request,
            String result,
            Throwable error,
            AgentChatContext context) {
        if (currentStep == null || request == null) return;
        Step step = step(context, currentStep);
        if (step == null) return;
        AgentTraceToolSnapshot tool = step.tools.get(toolKey(request));
        if (tool == null) {
            startTool(currentStep, request, context);
            tool = step.tools.get(toolKey(request));
        }
        tool.setFinishedAt(System.currentTimeMillis());
        tool.setDurationMs(duration(tool.getStartedAt(), tool.getFinishedAt()));
        tool.setResult(truncate(result));
        tool.setError(errorText(error));
        tool.setStatus(error == null ? "SUCCESS" : "ERROR");
    }

    private Integer currentStep(AgentChatContext context) {
        Runtime runtime = runtime(context);
        if (runtime == null || runtime.steps.isEmpty()) return null;
        return new ArrayList<Integer>(runtime.steps.keySet()).get(runtime.steps.size() - 1);
    }

    private Step step(AgentChatContext context, int currentStep) {
        Runtime runtime = runtime(context);
        return runtime == null ? null : runtime.steps.get(currentStep);
    }

    private Runtime runtime(AgentChatContext context) {
        Object value = context == null ? null : context.getExtension(RUNTIME_KEY);
        return value instanceof Runtime ? (Runtime) value : null;
    }

    private void publish(AgentTraceSnapshot snapshot) {
        if (collector == null || snapshot == null) return;
        try {
            collector.onSnapshot(snapshot);
        } catch (RuntimeException ignored) {
            // Observability is deliberately isolated from the Agent's main execution path.
        }
    }

    private static String toolKey(ToolExecutionRequest request) {
        return request.id() == null
                ? request.name() + "@" + System.identityHashCode(request)
                : request.id();
    }

    private static long duration(long start, long end) {
        return start <= 0 || end <= 0 ? 0 : Math.max(0, end - start);
    }

    private static String errorText(Throwable error) {
        return error == null ? null : truncate(error.getMessage());
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) return value;
        return value.substring(0, MAX_TEXT_LENGTH) + "...(truncated)";
    }

    private static class Runtime {
        private String memoryId;
        private String question;
        private long startedAt;
        private boolean finished;
        private final Map<Integer, Step> steps = new LinkedHashMap<Integer, Step>();
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    }

    private static class Step {
        private int step;
        private long startedAt;
        private long finishedAt;
        private long modelStartedAt;
        private long modelFinishedAt;
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private long cachedTokens;
        private String error;
        private final Map<String, AgentTraceToolSnapshot> tools =
                new LinkedHashMap<String, AgentTraceToolSnapshot>();
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
    }
}
