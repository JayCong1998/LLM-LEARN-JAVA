package com.jaycong.know.engine.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jaycong.know.engine.common.api.PageResponse;
import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.constant.DocumentStatus;
import com.jaycong.know.engine.document.constant.KnowledgeBaseType;
import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.dto.DocumentUploadParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.mapper.KnowledgeDocumentMapper;
import com.jaycong.know.engine.document.mapper.KnowledgeSegmentMapper;
import com.jaycong.know.engine.document.service.KnowledgeDocumentService;
import com.jaycong.know.engine.minio.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识文档的持久化、查询及文本上传切片服务。
 */
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {
    /**
     * 默认片段最大字符数。
     */
    private static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * 知识文档数据访问对象。
     */
    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 知识片段数据访问对象。
     */
    private final KnowledgeSegmentMapper segmentMapper;

    /**
     * 创建知识文档服务。
     *
     * @param documentMapper 知识文档数据访问对象
     * @param segmentMapper  知识片段数据访问对象
     */
    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper documentMapper, KnowledgeSegmentMapper segmentMapper) {
        this.documentMapper = documentMapper;
        this.segmentMapper = segmentMapper;
    }

    @Autowired
    private FileStorageService fileStorageService;


    /**
     * 创建未切片的知识文档。
     *
     * @param documentRequest 文档创建请求
     * @return 已创建的知识文档
     */
    @Override
    public KnowledgeDocument create(DocumentRequest documentRequest) {
        if (documentRequest == null || blank(documentRequest.title()))
            throw new IllegalArgumentException("title must not be blank");
        KnowledgeDocument document = new KnowledgeDocument();
        apply(document, documentRequest);
        document.setStatus(DocumentStatus.INIT);
        document.setDeleted(0);
        documentMapper.insert(document);
        return document;
    }

    /**
     * 根据主键查询未删除的知识文档。
     *
     * @param id 文档主键
     * @return 文档详情
     */
    @Override
    public KnowledgeDocument get(Long id) {
        KnowledgeDocument document = documentMapper.selectById(id);
        if (document == null || Integer.valueOf(1).equals(document.getDeleted()))
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识文档不存在");
        return document;
    }

    /**
     * 查询全部未删除的知识文档。
     *
     * @return 文档列表
     */
    @Override
    public List<KnowledgeDocument> list() {
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getDeleted, 0));
    }

    /**
     * 分页查询未删除的知识文档，并按标题、状态和知识库类型筛选。
     *
     * @param current           当前页码，从 1 开始
     * @param size              每页记录数
     * @param docTitle          可选的文档标题模糊查询条件
     * @param status            可选的文档状态精确查询条件
     * @param knowledgeBaseType 可选的知识库类型精确查询条件
     * @return 包含文档记录和分页信息的查询结果
     */
    @Override
    public PageResponse<KnowledgeDocument> page(long current, long size, String docTitle, String status,
                                                String knowledgeBaseType) {
        Page<KnowledgeDocument> documentPage = new Page<>(current, size);
        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getDeleted, 0)
                .like(!blank(docTitle), KnowledgeDocument::getDocTitle, docTitle)
                .eq(!blank(status), KnowledgeDocument::getStatus, status)
                .eq(!blank(knowledgeBaseType), KnowledgeDocument::getKnowledgeBaseType, knowledgeBaseType)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        return PageResponse.from(documentMapper.selectPage(documentPage, queryWrapper));
    }

    /**
     * 更新文档基础信息，不改变文档处理状态。
     *
     * @param id              文档主键
     * @param documentRequest 文档更新请求
     * @return 更新后的知识文档
     */
    @Override
    public KnowledgeDocument update(Long id, DocumentRequest documentRequest) {
        KnowledgeDocument document = get(id);
        apply(document, documentRequest);
        documentMapper.updateById(document);
        return document;
    }

    @Override
    public void updateStatus(Long documentId, DocumentStatus status) {
        LambdaUpdateWrapper<KnowledgeDocument> queryWrapper = new LambdaUpdateWrapper<>();
        queryWrapper.set(KnowledgeDocument::getStatus, status).eq(KnowledgeDocument::getId, documentId);
        documentMapper.update(queryWrapper);
    }

    /**
     * 软删除指定知识文档。
     *
     * @param id 文档主键
     */
    @Override
    public void delete(Long id) {
        documentMapper.deleteById(id);
    }

    /**
     * 校验文档请求并将可编辑字段复制到文档实体。
     *
     * @param document        待更新的文档实体
     * @param documentRequest 文档创建或更新请求
     */
    private void apply(KnowledgeDocument document, DocumentRequest documentRequest) {
        if (documentRequest == null || blank(documentRequest.title()))
            throw new IllegalArgumentException("title must not be blank");
        document.setDocTitle(documentRequest.title());
        document.setDescription(documentRequest.description());
        document.setKnowledgeBaseType(KnowledgeBaseType.valueOf(documentRequest.knowledgeBaseType()));
        document.setExtension(documentRequest.extension());
    }

    /**
     * 判断文本是否为空或仅包含空白字符。
     *
     * @param value 待判断文本
     * @return 文本为空或空白时返回 true
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
