package cloud.changlu.agentforge.service.common;

/**
 * 统一错误返回对象。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ApiErrorVo {

    private int status;
    private String message;

    public ApiErrorVo() {}

    public ApiErrorVo(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
