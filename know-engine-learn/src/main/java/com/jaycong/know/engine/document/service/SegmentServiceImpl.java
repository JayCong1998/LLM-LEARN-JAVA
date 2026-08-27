package com.jaycong.know.engine.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.dto.SegmentRequest;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.mapper.KnowledgeSegmentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识片段的基础 CRUD 服务。
 */
@Service
public class SegmentServiceImpl implements SegmentService {
    /**
     * 知识片段数据访问对象。
     */
    private final KnowledgeSegmentMapper mapper;

    /**
     * 创建知识片段服务。
     *
     * @param mapper 知识片段数据访问对象
     */
    public SegmentServiceImpl(KnowledgeSegmentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 创建尚未写入向量库的文本片段。
     *
     * @param segmentRequest 片段创建请求
     * @return 已创建的知识片段
     */
    @Override
    public KnowledgeSegment create(SegmentRequest segmentRequest) {
        KnowledgeSegment segment = new KnowledgeSegment();
        apply(segment, segmentRequest);
        segment.setStatus("STORED");
        segment.setDeleted(0);
        mapper.insert(segment);
        return segment;
    }

    /**
     * 根据主键查询未删除的知识片段。
     *
     * @param id 片段主键
     * @return 片段详情
     */
    @Override
    public KnowledgeSegment get(Long id) {
        KnowledgeSegment segment = mapper.selectById(id);
        if (segment == null || Integer.valueOf(1).equals(segment.getDeleted()))
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识片段不存在");
        return segment;
    }

    /**
     * 查询未删除的知识片段，可按文档主键筛选。
     *
     * @param documentId 可选的文档主键
     * @return 按片段顺序排列的片段列表
     */
    @Override
    public List<KnowledgeSegment> list(Long documentId) {
        return mapper.selectList(new LambdaQueryWrapper<KnowledgeSegment>().eq(KnowledgeSegment::getDeleted, 0).eq(documentId != null, KnowledgeSegment::getDocumentId, documentId).orderByAsc(KnowledgeSegment::getChunkOrder));
    }

    /**
     * 更新指定片段的文本和元数据。
     *
     * @param id 片段主键
     * @param segmentRequest 片段更新请求
     * @return 更新后的知识片段
     */
    @Override
    public KnowledgeSegment update(Long id, SegmentRequest segmentRequest) {
        KnowledgeSegment segment = get(id);
        apply(segment, segmentRequest);
        mapper.updateById(segment);
        return segment;
    }

    /**
     * 软删除指定知识片段。
     *
     * @param id 片段主键
     */
    @Override
    public void delete(Long id) {
        KnowledgeSegment segment = get(id);
        segment.setDeleted(1);
        mapper.updateById(segment);
    }

    /**
     * 校验片段请求并复制可编辑字段。
     *
     * @param segment 待更新的片段实体
     * @param segmentRequest 片段创建或更新请求
     */
    private void apply(KnowledgeSegment segment, SegmentRequest segmentRequest) {
        if (segmentRequest == null || segmentRequest.getDocumentId() == null || segmentRequest.getText() == null || segmentRequest.getText().isBlank() || segmentRequest.getChunkOrder() == null)
            throw new IllegalArgumentException("documentId, text and chunkOrder are required");
        segment.setDocumentId(segmentRequest.getDocumentId());
        segment.setText(segmentRequest.getText());
        segment.setChunkOrder(segmentRequest.getChunkOrder());
        segment.setMetadata(segmentRequest.getMetadata());
        segment.setSkipEmbedding(segmentRequest.getSkipEmbedding() == null ? 0 : segmentRequest.getSkipEmbedding());
    }
}
