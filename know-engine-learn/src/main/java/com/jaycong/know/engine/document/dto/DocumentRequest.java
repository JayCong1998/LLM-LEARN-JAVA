package com.jaycong.know.engine.document.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建或更新知识文档的请求参数。
 */
public record DocumentRequest(
        /**
         * 文档标题。
         */
        @NotBlank(message = "文档标题不能为空")
        String title,
        /**
         * 文档描述。
         */
        String description,
        /**
         * 知识库类型，例如 DOCUMENT_SEARCH。
         */
        String knowledgeBaseType,
        /**
         * JSON 格式的扩展属性。
         */
        String extension
) {
}
