package com.jaycong.know.engine.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建或更新知识片段的请求参数。
 */
public record SegmentRequest(
        /**
         * 所属知识文档主键。
         */
        @NotNull(message = "所属文档不能为空")
        @Positive(message = "所属文档必须为正数")
        Long documentId,
        /**
         * 片段文本内容。
         */
        @NotBlank(message = "片段文本不能为空")
        String text,
        /**
         * 文档内片段顺序，从 1 开始。
         */
        @NotNull(message = "片段顺序不能为空")
        @Positive(message = "片段顺序必须为正数")
        Integer chunkOrder,
        /**
         * JSON 格式的片段元数据。
         */
        String metadata,
        /**
         * 是否跳过向量嵌入生成：0-否，1-是。
         */
        Integer skipEmbedding
) {
}
