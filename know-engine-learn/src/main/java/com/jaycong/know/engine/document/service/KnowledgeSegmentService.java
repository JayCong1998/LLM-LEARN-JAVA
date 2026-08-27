package com.jaycong.know.engine.document.service;

import com.jaycong.know.engine.document.dto.SegmentRequest;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;

import java.util.List;

/**
 * 知识片段应用服务定义。
 */
public interface KnowledgeSegmentService {
    /**
     * 创建尚未写入向量库的知识片段。
     *
     * @param segmentRequest 片段创建请求
     * @return 已创建的知识片段
     * @throws IllegalArgumentException 必填字段缺失或不符合要求时抛出
     */
    KnowledgeSegment create(SegmentRequest segmentRequest);

    /**
     * 根据主键查询未删除的知识片段。
     *
     * @param segmentId 片段主键
     * @return 片段详情
     * @throws com.jaycong.know.engine.common.error.BusinessException 片段不存在或已删除时抛出
     */
    KnowledgeSegment get(Long segmentId);

    /**
     * 查询未删除的知识片段，可按文档主键筛选。
     *
     * @param documentId 可选的文档主键，为空时查询全部文档的片段
     * @return 按片段顺序排列的片段列表
     */
    List<KnowledgeSegment> list(Long documentId);

    /**
     * 更新知识片段的文本内容和元数据。
     *
     * @param segmentId 片段主键
     * @param segmentRequest 片段更新请求
     * @return 更新后的知识片段
     */
    KnowledgeSegment update(Long segmentId, SegmentRequest segmentRequest);

    /**
     * 软删除指定知识片段。
     *
     * @param segmentId 片段主键
     */
    void delete(Long segmentId);
}
