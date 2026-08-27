package com.jaycong.know.engine.document.controller;

import com.jaycong.know.engine.common.api.ApiResponse;
import com.jaycong.know.engine.document.dto.SegmentRequest;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.service.KnowledgeSegmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识片段管理接口，提供片段 CRUD 与按文档查询能力。
 */
@RestController
@RequestMapping("/api/segment")
@Validated
public class KnowledgeSegmentController {

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;


    /**
     * 创建文本片段。
     *
     * @param request 片段创建请求
     * @return 已创建片段
     */
    @PostMapping
    public ApiResponse<KnowledgeSegment> create(@Valid @RequestBody SegmentRequest request) {
        return ApiResponse.success(knowledgeSegmentService.create(request));
    }

    /**
     * 根据主键查询未删除的片段。
     *
     * @param id 片段主键
     * @return 片段详情
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeSegment> get(@PathVariable @Positive(message = "片段主键必须为正数") Long id) {
        return ApiResponse.success(knowledgeSegmentService.get(id));
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
        return ApiResponse.success(knowledgeSegmentService.list(documentId));
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
        return ApiResponse.success(knowledgeSegmentService.update(id, request));
    }

    /**
     * 软删除指定片段。
     *
     * @param id 片段主键
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @Positive(message = "片段主键必须为正数") Long id) {
        knowledgeSegmentService.delete(id);
        return ApiResponse.success();
    }
}
