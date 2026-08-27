package com.jaycong.know.engine.document.controller;

import com.jaycong.know.engine.common.api.ApiResponse;
import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.dto.FileUploadRequest;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.service.DocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识文档管理接口，提供文档 CRUD 与纯文本上传切片能力。
 */
@RestController
@RequestMapping("/api/document")
@Validated
public class DocumentController {

    /**
     * 知识文档应用服务。
     */
    @Autowired
    private DocumentService documentService;

    /**
     * 上传纯文本，创建文档并生成初始片段。
     *
     * @return 文档主键和片段数量
     */
    @PostMapping("/upload")
    public ApiResponse uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "version", required = false, defaultValue = "1.0.0") String version,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam("description") String description,
            @RequestParam("knowledgeBaseType") String knowledgeBaseType
    ) {
        FileUploadRequest request = new FileUploadRequest(file, title, version, tableName, description, knowledgeBaseType);
        documentService.uploadFile(request);
        return ApiResponse.success();
    }

    /**
     * 创建不包含文本片段的知识文档。
     *
     * @param request 文档创建请求
     * @return 已创建文档
     */
    @PostMapping
    public ApiResponse<KnowledgeDocument> create(@Valid @RequestBody DocumentRequest request) {
        return ApiResponse.success(documentService.create(request));
    }

    /**
     * 根据主键查询未删除的文档。
     *
     * @param id 文档主键
     * @return 文档详情
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocument> get(@PathVariable @Positive(message = "文档主键必须为正数") Long id) {
        return ApiResponse.success(documentService.get(id));
    }

    /**
     * 查询全部未删除的文档。
     *
     * @return 文档列表
     */
    @GetMapping
    public ApiResponse<List<KnowledgeDocument>> list() {
        return ApiResponse.success(documentService.list());
    }

    /**
     * 更新文档的基础信息。
     *
     * @param id      文档主键
     * @param request 文档更新请求
     * @return 更新后的文档
     */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeDocument> update(
            @PathVariable @Positive(message = "文档主键必须为正数") Long id,
            @Valid @RequestBody DocumentRequest request) {
        return ApiResponse.success(documentService.update(id, request));
    }

    /**
     * 软删除指定文档。
     *
     * @param id 文档主键
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive(message = "文档主键必须为正数") Long id) {
        documentService.delete(id);
        return ApiResponse.success();
    }
}
