package cloud.changlu.agentforge.service.common;

import cloud.changlu.agentforge.service.common.exception.AgentForgeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理，将异常转换为带可读 message 的统一结构，便于前端展示与排障。
 *
 * @author changlu
 * @since 2026-10-14
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AgentForgeException.class)
    public ResponseEntity<ApiErrorVo> handleBusiness(AgentForgeException e) {
        log.warn("Business exception[{}]: {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorVo(HttpStatus.BAD_REQUEST.value(), e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorVo> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorVo(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
    }

    /** 路径变量/请求参数类型不匹配属于客户端入参错误，返回 400，避免误记为服务端 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorVo> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Request parameter type mismatch: {}={}", e.getName(), e.getValue());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorVo(HttpStatus.BAD_REQUEST.value(), "请求参数格式不正确: " + e.getName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorVo> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorVo(HttpStatus.INTERNAL_SERVER_ERROR.value(), rootMessage(e)));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
                ? root.getClass().getSimpleName()
                : message;
    }
}
