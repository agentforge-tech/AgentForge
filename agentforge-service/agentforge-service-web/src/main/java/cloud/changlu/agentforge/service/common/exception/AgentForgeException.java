package cloud.changlu.agentforge.service.common.exception;

/**
 * AgentForge 统一业务异常基类。
 *
 * <p>项目内可预期的业务失败统一继承该异常，携带 {@code code} 便于前端与日志定位问题； 其 message 直接面向用户，可作为页面提示或对话回答内容展示。
 *
 * <p>约定：
 *
 * <ul>
 *   <li>{@link #code} 为稳定的业务错误码，前端可据此做差异化处理；
 *   <li>{@link #getMessage()} 为可直接展示给用户的中文提示；
 *   <li>未捕获时由 {@code GlobalExceptionHandler} 统一转换为带 code 的错误响应。
 * </ul>
 */
public class AgentForgeException extends RuntimeException {

    /** 通用业务错误码 */
    public static final String CODE_BUSINESS = "AGENTFORGE_BUSINESS_ERROR";

    private final String code;

    public AgentForgeException(String message) {
        this(CODE_BUSINESS, message);
    }

    public AgentForgeException(String code, String message) {
        super(message);
        this.code = code == null || code.trim().isEmpty() ? CODE_BUSINESS : code;
    }

    public AgentForgeException(String message, Throwable cause) {
        this(CODE_BUSINESS, message, cause);
    }

    public AgentForgeException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null || code.trim().isEmpty() ? CODE_BUSINESS : code;
    }

    public String getCode() {
        return code;
    }
}
