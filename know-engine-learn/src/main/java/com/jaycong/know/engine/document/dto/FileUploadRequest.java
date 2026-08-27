package com.jaycong.know.engine.document.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传请求参数封装，将 {@code DocumentController#uploadFile} 的全部入参聚合成不可变记录，
 * 便于在控制器与服务层之间传递。
 *
 * @param file              上传的文件
 * @param title             文档标题
 * @param version           文档版本号，未传入时由调用方填充
 * @param tableName         目标表名，仅当知识库类型为 DATA_QUERY 时需要
 * @param description       文档描述
 * @param knowledgeBaseType 知识库类型
 */
public record FileUploadRequest(
        MultipartFile file,
        String title,
        String version,
        String tableName,
        String description,
        String knowledgeBaseType) {
}