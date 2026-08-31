package com.jaycong.know.engine.document.controller;

import com.jaycong.know.engine.common.api.ApiResponse;
import com.jaycong.know.engine.common.api.PageResponse;
import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.dto.DocumentSplitParam;
import com.jaycong.know.engine.document.dto.DocumentUploadParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.service.DocumentProcessService;
import com.jaycong.know.engine.document.service.KnowledgeDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识文档管理接口，提供文档 CRUD 与纯文本上传切片能力。
 */
@RestController
@RequestMapping("/api/document")
@Validated
public class KnowledgeDocumentController {

    @Autowired
    private KnowledgeDocumentService documentService;

    @Autowired
    private DocumentProcessService documentProcessService;

    /**
     * 上传纯文本，创建文档并生成初始片段。
     *
     * @return 文档主键和片段数量
     */
    @PostMapping("/upload")
    public ApiResponse uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam("description") String description,
            @RequestParam("knowledgeBaseType") String knowledgeBaseType
    ) {
        DocumentUploadParam request = new DocumentUploadParam(file, title, tableName, description, knowledgeBaseType);
        documentProcessService.uploadFile(request);
        return ApiResponse.success();
    }

    @PostMapping("/convertDocument/{documentId}")
    public ApiResponse convertDocument(@PathVariable Long documentId, @RequestParam("minerUdocUrl") String minerUdocUrl) throws Exception {
        documentProcessService.manulConvertDocument(documentId,minerUdocUrl);
        return ApiResponse.success();
    }

    /**
     * 对文档进行切分
     * 注意：此方法为手动触发切分接口，正常流程由事件驱动自动执行
     *
     * @param documentId 文档ID
     * @return 切分后的片段数量
     */
    @PostMapping("/split/{documentId}")
    public ApiResponse splitDocument(@PathVariable Long documentId,
                                     @RequestParam("splitType") String splitType,
                                     @RequestParam("chunkSize") Integer chunkSize,
                                     @RequestParam(value = "overlap", required = false) Integer overlap,
                                     @RequestParam(value = "regex", required = false) String regex,
                                     @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
                                     @RequestParam(value = "separator", required = false) String separator
    ) {
        KnowledgeDocument document = documentService.getDocumentById(documentId);
        documentProcessService.split(document, new DocumentSplitParam(splitType, chunkSize, overlap, titleLevel, separator, regex));
        return ApiResponse.success();
    }


    /**
     * 分页查询未删除的文档，并支持按标题、状态和知识库类型筛选。
     *
     * @param current           当前页码，从 1 开始
     * @param size              每页记录数
     * @param docTitle          可选的文档标题模糊查询条件
     * @param status            可选的文档状态精确查询条件
     * @param knowledgeBaseType 可选的知识库类型精确查询条件
     * @return 包含文档记录和分页信息的统一响应
     */
    @GetMapping("/page")
    public ApiResponse<PageResponse<KnowledgeDocument>> page(
            @RequestParam @Positive(message = "页码必须为正数") long current,
            @RequestParam @Positive(message = "每页数量必须为正数") long size,
            @RequestParam(required = false) String docTitle,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String knowledgeBaseType) {
        return ApiResponse.success(documentService.pageDocuments(current, size, docTitle, status, knowledgeBaseType));
    }

    /**
     * 创建不包含文本片段的知识文档。
     *
     * @param request 文档创建请求
     * @return 已创建文档
     */
    @PostMapping
    public ApiResponse<KnowledgeDocument> create(@Valid @RequestBody DocumentRequest request) {
        return ApiResponse.success(documentService.createDocument(request));
    }

    /**
     * 根据主键查询未删除的文档。
     *
     * @param id 文档主键
     * @return 文档详情
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocument> getById(@PathVariable @Positive(message = "文档主键必须为正数") Long id) {
        return ApiResponse.success(documentService.getDocumentById(id));
    }

    /**
     * 更新文档的基础信息。
     *
     * @param id      文档主键
     * @param request 文档更新请求
     * @return 更新后的文档
     */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeDocument> updateById(
            @PathVariable @Positive(message = "文档主键必须为正数") Long id,
            @Valid @RequestBody DocumentRequest request) {
        return ApiResponse.success(documentService.updateDocument(id, request));
    }

    /**
     * 软删除指定文档。
     *
     * @param id 文档主键
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteById(@PathVariable @Positive(message = "文档主键必须为正数") Long id) {
        documentService.removeById(id);
        return ApiResponse.success();
    }
}
