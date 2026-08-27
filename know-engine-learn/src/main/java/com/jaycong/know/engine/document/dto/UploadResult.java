package com.jaycong.know.engine.document.dto;

/**
 * 文本上传与切片完成后的结果。
 *
 * @param documentId 新建文档的主键
 * @param segmentCount 本次生成的片段数量
 */
public record UploadResult(
        Long documentId,
        int segmentCount) {
}
