package com.jaycong.know.engine.common.error;

import com.jaycong.know.engine.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * REST 接口全局异常处理器，将应用异常转换为统一响应结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 全局异常日志记录器。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理可预期的业务异常。
     *
     * @param exception 业务异常
     * @return 包含业务错误码和提示的响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode, exception.getMessage()));
    }

    /**
     * 处理请求体绑定和参数校验异常。
     *
     * @param exception 参数校验异常
     * @return 请求参数错误响应
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidationException(Exception exception) {
        String message = extractValidationMessage(exception);
        return invalidRequest(message);
    }

    /**
     * 处理方法参数约束校验异常。
     *
     * @param exception 参数约束校验异常
     * @return 请求参数错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException exception) {
        return invalidRequest(exception.getMessage());
    }

    /**
     * 处理 Spring 方法级参数校验异常。
     *
     * @param exception 方法参数校验异常
     * @return 请求参数错误响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidationException(
            HandlerMethodValidationException exception) {
        String message = exception.getAllErrors().stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        return invalidRequest(message);
    }

    /**
     * 处理请求体缺失、格式错误或无法反序列化的异常。
     *
     * @param exception 请求体读取异常
     * @return 请求参数错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadableException(HttpMessageNotReadableException exception) {
        return invalidRequest(ErrorCode.INVALID_REQUEST.getMessage());
    }

    /**
     * 处理业务参数主动校验产生的异常。
     *
     * @param exception 非法参数异常
     * @return 请求参数错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException exception) {
        return invalidRequest(exception.getMessage());
    }

    /**
     * 处理未被其他处理器匹配的系统异常。
     *
     * @param exception 未预期异常
     * @return 系统内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("发生未预期的系统异常", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 创建请求参数错误响应。
     *
     * @param message 面向调用方的错误提示
     * @return 请求参数错误响应
     */
    private ResponseEntity<ApiResponse<Void>> invalidRequest(String message) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message));
    }

    /**
     * 从参数绑定异常中提取首个校验提示。
     *
     * @param exception 参数绑定异常
     * @return 首个校验提示；无法提取时返回默认提示
     */
    private String extractValidationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return methodArgumentNotValidException.getBindingResult().getAllErrors().stream()
                    .findFirst()
                    .map(DefaultMessageSourceResolvable::getDefaultMessage)
                    .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        }
        if (exception instanceof BindException bindException) {
            return bindException.getBindingResult().getAllErrors().stream()
                    .findFirst()
                    .map(DefaultMessageSourceResolvable::getDefaultMessage)
                    .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        }
        return ErrorCode.INVALID_REQUEST.getMessage();
    }
}
