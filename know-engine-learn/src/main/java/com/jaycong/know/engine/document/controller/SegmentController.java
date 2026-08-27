package com.jaycong.know.engine.document.controller;

import com.jaycong.know.engine.common.api.ApiResponse;
import com.jaycong.know.engine.document.dto.SegmentRequest;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.service.SegmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识片段管理接口，提供片段 CRUD 与按文档查询能力。
 */
@RestController
@RequestMapping("/api/segment")
@Validated
public class SegmentController {
    /**
     * 知识片段应用服务。
     */
    private final SegmentService service;

    /**
     * 创建知识片段控制器。
     *
     * @param service 知识片段应用服务
     */
    public SegmentController(SegmentService service) {
        this.service = service;
    }

    /**
     * 创建文本片段。
     *
     * @param request 片段创建请求
     * @return 已创建片段
     */
    @PostMapping
    public ApiResponse<KnowledgeSegment> create(@Valid @RequestBody SegmentRequest request) {
        return ApiResponse.success(service.create(request));
    }

    /**
     * 根据主键查询未删除的片段。
     *
     * @param id 片段主键
     * @return 片段详情
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeSegment> get(@PathVariable @Positive(message = "片段主键必须为正数") Long id) {
        return ApiResponse.success(service.get(id));
    }

    /**
     * 查询片段；传入文档主键时仅返回该文档的片段。
     *
     * @param documentId 可选的文档主键
     * @return 片段列表
     */
    @GetMapping
    public ApiResponse<List<KnowledgeSegment>> list(
            @RequestParam(required = false) @Positive(message = "文档主键必须为正数") Long documentId) {
        return ApiResponse.success(service.list(documentId));
    }

    /**
     * 更新片段内容和元数据。
     *
     * @param id      片段主键
     * @param request 片段更新请求
     * @return 更新后的片段
     */
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeSegment> update(
            @PathVariable @Positive(message = "片段主键必须为正数") Long id,
            @Valid @RequestBody SegmentRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    /**
     * 软删除指定片段。
     *
     * @param id 片段主键
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive(message = "片段主键必须为正数") Long id) {
        service.delete(id);
        return ApiResponse.success();
    }
}
