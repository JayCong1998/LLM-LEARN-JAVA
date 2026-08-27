package com.jaycong.know.engine.common.api;

import com.jaycong.know.engine.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseTest {

    @Test
    void successWithDataUsesDefaultSuccessMessage() {
        ApiResponse<String> response = ApiResponse.success("知识文档");

        assertEquals(0, response.getCode());
        assertEquals("操作成功", response.getMessage());
        assertEquals("知识文档", response.getData());
    }

    @Test
    void successWithoutDataReturnsNullData() {
        ApiResponse<Void> response = ApiResponse.success();

        assertEquals(0, response.getCode());
        assertNull(response.getData());
    }

    @Test
    void failWithErrorCodeUsesMappedCodeAndMessage() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND);

        assertEquals(40400, response.getCode());
        assertEquals("资源不存在", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    void failWithCustomMessageKeepsMappedBusinessCode() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND, "知识文档不存在");

        assertEquals(40400, response.getCode());
        assertEquals("知识文档不存在", response.getMessage());
    }
}
