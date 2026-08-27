package com.jaycong.know.engine.common.error;

/**
 * 用于向接口层传递可预期业务失败的异常。
 */
public class BusinessException extends RuntimeException {
    /**
     * 异常对应的统一错误码。
     */
    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认提示创建业务异常。
     *
     * @param errorCode 统一错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    /**
     * 使用指定提示创建业务异常。
     *
     * @param errorCode 统一错误码
     * @param message 面向调用方的异常提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 获取异常对应的统一错误码。
     *
     * @return 统一错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
