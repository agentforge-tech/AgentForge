package cloud.changlu.agentforge.agent.extend.middlewares.read.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取工具的稳定返回契约。新增场景通过 kind 与 extra 扩展，不改动已有字段语义。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ReadBatchResult {

    public static final String SCHEMA = "agentforge.read.v1";

    private String schema = SCHEMA;
    private boolean ok = true;
    private List<ReadResult> results = new ArrayList<ReadResult>();
    private String errorCode;
    private String message;

    public static ReadBatchResult failure(String errorCode, String message) {
        ReadBatchResult result = new ReadBatchResult();
        result.setOk(false);
        result.setErrorCode(errorCode);
        result.setMessage(message);
        return result;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public List<ReadResult> getResults() {
        return results;
    }

    public void setResults(List<ReadResult> results) {
        this.results = results == null ? new ArrayList<ReadResult>() : results;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
