package cloud.changlu.agentforge.service.common;

/**
 * 统一错误返回对象。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ApiErrorVo {

    private int status;

    /** 业务错误码，仅业务异常携带 */
    private String code;

    private String message;

    public ApiErrorVo() {}

    public ApiErrorVo(int status, String message) {
        this(status, null, message);
    }

    public ApiErrorVo(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
