package com.jaycong.know.engine.common.error;

import org.springframework.http.HttpStatus;

/**
 * 应用统一错误码及其 HTTP 状态映射。
 */
public enum ErrorCode {
    /**
     * 操作成功。
     */
    SUCCESS(0, HttpStatus.OK, "操作成功"),
    /**
     * 请求参数不符合接口约束。
     */
    INVALID_REQUEST(400, HttpStatus.BAD_REQUEST, "请求参数错误"),
    /**
     * 请求的业务资源不存在。
     */
    RESOURCE_NOT_FOUND(404, HttpStatus.NOT_FOUND, "资源不存在"),
    /**
     * 当前业务状态与请求操作冲突。
     */
    BUSINESS_CONFLICT(409, HttpStatus.CONFLICT, "业务冲突"),
    /**
     * 未预期的系统内部错误。
     */
    INTERNAL_ERROR(500, HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    /**
     * 业务状态码。
     */
    private final int code;
    /**
     * 对应的 HTTP 状态。
     */
    private final HttpStatus httpStatus;
    /**
     * 默认响应提示。
     */
    private final String message;

    /**
     * 创建统一错误码。
     *
     * @param code       业务状态码
     * @param httpStatus 对应的 HTTP 状态
     * @param message    默认响应提示
     */
    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
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
     * 获取对应的 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * 获取默认响应提示。
     *
     * @return 默认响应提示
     */
    public String getMessage() {
        return message;
    }
}
