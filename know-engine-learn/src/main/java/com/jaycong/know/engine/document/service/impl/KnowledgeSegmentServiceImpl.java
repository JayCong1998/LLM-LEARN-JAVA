package com.jaycong.know.engine.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.constant.SegmentStatus;
import com.jaycong.know.engine.document.dto.SegmentRequest;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.mapper.KnowledgeSegmentMapper;
import com.jaycong.know.engine.document.service.KnowledgeSegmentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识片段的基础 CRUD 服务。
 * <p>
 * 继承 {@link ServiceImpl} 复用 MyBatis-Plus 通用 CRUD；{@code removeById}、{@code saveBatch} 等基础方法
 * 直接由父类提供，软删除由实体 {@code @TableLogic} 字段自动处理。
 */
@Service
public class KnowledgeSegmentServiceImpl extends ServiceImpl<KnowledgeSegmentMapper, KnowledgeSegment>
        implements KnowledgeSegmentService {

    /**
     * 创建尚未写入向量库的文本片段。
     *
     * @param segmentRequest 片段创建请求
     * @return 已创建的知识片段
     */
    @Override
    public KnowledgeSegment createSegment(SegmentRequest segmentRequest) {
        KnowledgeSegment segment = new KnowledgeSegment();
        apply(segment, segmentRequest);
        segment.setStatus(SegmentStatus.STORED);
        segment.setDeleted(0);
        baseMapper.insert(segment);
        return segment;
    }

    /**
     * 根据主键查询未删除的知识片段。
     *
     * @param segmentId 片段主键
     * @return 片段详情
     */
    @Override
    public KnowledgeSegment getSegmentById(Long segmentId) {
        KnowledgeSegment segment = baseMapper.selectById(segmentId);
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
    public List<KnowledgeSegment> listByDocumentId(Long documentId) {
        return baseMapper.selectList(new LambdaQueryWrapper<KnowledgeSegment>()
                .eq(KnowledgeSegment::getDeleted, 0)
                .eq(documentId != null, KnowledgeSegment::getDocumentId, documentId)
                .orderByAsc(KnowledgeSegment::getChunkOrder));
    }

    /**
     * 更新指定片段的文本和元数据。
     *
     * @param segmentId      片段主键
     * @param segmentRequest 片段更新请求
     * @return 更新后的知识片段
     */
    @Override
    public KnowledgeSegment updateSegment(Long segmentId, SegmentRequest segmentRequest) {
        KnowledgeSegment segment = getSegmentById(segmentId);
        apply(segment, segmentRequest);
        baseMapper.updateById(segment);
        return segment;
    }

    /**
     * 校验片段请求并复制可编辑字段。
     *
     * @param segment        待更新的片段实体
     * @param segmentRequest 片段创建或更新请求
     */
    private void apply(KnowledgeSegment segment, SegmentRequest segmentRequest) {
         if (segmentRequest == null || segmentRequest.documentId() == null || segmentRequest.text() == null || segmentRequest.text().isBlank() || segmentRequest.chunkOrder() == null)
            throw new IllegalArgumentException("documentId, text and chunkOrder are required");
        segment.setDocumentId(segmentRequest.documentId());
        segment.setText(segmentRequest.text());
        segment.setChunkOrder(segmentRequest.chunkOrder());
        segment.setMetadata(segmentRequest.metadata());
        segment.setSkipEmbedding(segmentRequest.skipEmbedding() == null ? 0 : segmentRequest.skipEmbedding());
    }
}
