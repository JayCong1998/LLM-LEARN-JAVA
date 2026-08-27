package com.jaycong.know.engine.common.api;

import com.jaycong.know.engine.common.error.ErrorCode;

/**
 * REST 接口统一响应结构。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {
    /**
     * 业务状态码，成功时为 0。
     */
    private final int code;
    /**
     * 面向调用方的响应提示。
     */
    private final String message;
    /**
     * 成功时返回的业务数据，失败时为 null。
     */
    private final T data;

    /**
     * 创建统一响应对象。
     *
     * @param code    业务状态码
     * @param message 响应提示
     * @param data    业务数据
     */
    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建不包含业务数据的成功响应。
     *
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 创建包含业务数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return success(ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 使用指定提示和业务数据创建成功响应。
     *
     * @param message 响应提示
     * @param data    业务数据
     * @param <T>     业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), message, data);
    }

    /**
     * 使用错误码的默认提示创建失败响应。
     *
     * @param errorCode 统一错误码
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    /**
     * 使用统一错误码和指定提示创建失败响应。
     *
     * @param errorCode 统一错误码
     * @param message   响应提示
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    /**
     * 使用业务状态码和指定提示创建失败响应。
     *
     * @param code    业务状态码
     * @param message 响应提示
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 获取业务状态码。
     *
     * @return 业务状态码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取响应提示。
     *
     * @return 响应提示
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取业务数据。
     *
     * @return 业务数据
     */
    public T getData() {
        return data;
    }
}
