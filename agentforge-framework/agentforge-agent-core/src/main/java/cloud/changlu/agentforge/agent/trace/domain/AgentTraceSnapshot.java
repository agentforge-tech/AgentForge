package cloud.changlu.agentforge.agent.trace.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable trace payload shared by collectors and visualization clients. */
public class AgentTraceSnapshot {

    private String memoryId;
    private int questionRound;
    private int step;
    private String snapshotKind;
    private String question;
    private long startedAt;
    private long finishedAt;
    private long durationMs;
    private long modelStartedAt;
    private long modelFinishedAt;
    private long modelDurationMs;
    private long toolDurationMs;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private long cachedTokens;
    private boolean finished;
    private AgentTraceSnapshotStatus status;
    private String error;
    private List<AgentTraceToolSnapshot> tools = new ArrayList<AgentTraceToolSnapshot>();
    private Map<String, Object> attributes = new LinkedHashMap<String, Object>();

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public int getQuestionRound() {
        return questionRound;
    }

    public void setQuestionRound(int questionRound) {
        this.questionRound = questionRound;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public String getSnapshotKind() {
        return snapshotKind;
    }

    public void setSnapshotKind(String snapshotKind) {
        this.snapshotKind = snapshotKind;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getModelStartedAt() {
        return modelStartedAt;
    }

    public void setModelStartedAt(long modelStartedAt) {
        this.modelStartedAt = modelStartedAt;
    }

    public long getModelFinishedAt() {
        return modelFinishedAt;
    }

    public void setModelFinishedAt(long modelFinishedAt) {
        this.modelFinishedAt = modelFinishedAt;
    }

    public long getModelDurationMs() {
        return modelDurationMs;
    }

    public void setModelDurationMs(long modelDurationMs) {
        this.modelDurationMs = modelDurationMs;
    }

    public long getToolDurationMs() {
        return toolDurationMs;
    }

    public void setToolDurationMs(long toolDurationMs) {
        this.toolDurationMs = toolDurationMs;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public long getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(long cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public AgentTraceSnapshotStatus getStatus() {
        return status;
    }

    public void setStatus(AgentTraceSnapshotStatus status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<AgentTraceToolSnapshot> getTools() {
        return tools;
    }

    public void setTools(List<AgentTraceToolSnapshot> tools) {
        this.tools = tools;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
