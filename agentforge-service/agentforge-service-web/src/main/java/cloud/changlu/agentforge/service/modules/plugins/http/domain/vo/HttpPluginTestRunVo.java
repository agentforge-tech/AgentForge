package cloud.changlu.agentforge.service.modules.plugins.http.domain.vo;

public class HttpPluginTestRunVo {
    private boolean success;
    private String toolName;
    private long durationMs;
    private String result;
    private String error;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean v) {
        success = v;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String v) {
        toolName = v;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long v) {
        durationMs = v;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String v) {
        result = v;
    }

    public String getError() {
        return error;
    }

    public void setError(String v) {
        error = v;
    }
}
