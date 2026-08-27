package com.jaycong.know.engine.common.error;

import com.jaycong.know.engine.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsBusinessExceptionToConfiguredHttpStatusAndErrorCode() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识文档不存在"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(40400, response.getBody().getCode());
        assertEquals("知识文档不存在", response.getBody().getMessage());
        assertNull(response.getBody().getData());
    }

    @Test
    void mapsIllegalArgumentExceptionToInvalidRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("文档标题不能为空"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(40000, response.getBody().getCode());
        assertEquals("文档标题不能为空", response.getBody().getMessage());
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("database connection password"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(50000, response.getBody().getCode());
        assertEquals("系统内部错误", response.getBody().getMessage());
    }
}
