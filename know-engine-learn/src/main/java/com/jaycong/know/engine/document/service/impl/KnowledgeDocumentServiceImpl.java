package com.jaycong.know.engine.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jaycong.know.engine.common.api.PageResponse;
import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.constant.DocumentStatus;
import com.jaycong.know.engine.document.constant.KnowledgeBaseType;
import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.mapper.KnowledgeDocumentMapper;
import com.jaycong.know.engine.document.service.KnowledgeDocumentService;
import com.jaycong.know.engine.minio.FileStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 知识文档的持久化、查询及文本上传切片服务。
 * <p>
 * 继承 {@link ServiceImpl} 复用 MyBatis-Plus 通用 CRUD；{@code removeById} 等基础方法直接由父类提供，
 * 软删除由实体 {@code @TableLogic} 字段自动处理，无需自行实现。
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument>
        implements KnowledgeDocumentService {

    /**
     * 文件存储服务。
     */
    @Autowired
    private FileStorageService fileStorageService;

    /**
     * 创建未切片的知识文档。
     *
     * @param documentRequest 文档创建请求
     * @return 已创建的知识文档
     */
    @Override
    public KnowledgeDocument createDocument(DocumentRequest documentRequest) {
        if (documentRequest == null || blank(documentRequest.title()))
            throw new IllegalArgumentException("title must not be blank");
        KnowledgeDocument document = new KnowledgeDocument();
        apply(document, documentRequest);
        document.setStatus(DocumentStatus.INIT);
        document.setDeleted(0);
        baseMapper.insert(document);
        return document;
    }

    @Override
    public KnowledgeDocument getDocumentById(Long documentId) {
          KnowledgeDocument document = baseMapper.selectById(documentId);
        if (document == null || Integer.valueOf(1).equals(document.getDeleted()))
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识文档不存在");
        return document;
    }

    /**
     * 根据 DTO 更新文档基础信息。
     *
     * @param documentId      文档主键
     * @param documentRequest 文档更新请求
     * @return 更新后的文档实体
     */
    @Override
    public KnowledgeDocument updateDocument(Long documentId, DocumentRequest documentRequest) {
        KnowledgeDocument document = getDocumentById(documentId);
        apply(document, documentRequest);
        baseMapper.updateById(document);
        return document;
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
    public PageResponse<KnowledgeDocument> pageDocuments(long current, long size, String docTitle, String status,
                                                         String knowledgeBaseType) {
        Page<KnowledgeDocument> documentPage = new Page<>(current, size);
        LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getDeleted, 0)
                .like(!blank(docTitle), KnowledgeDocument::getDocTitle, docTitle)
                .eq(!blank(status), KnowledgeDocument::getStatus, status)
                .eq(!blank(knowledgeBaseType), KnowledgeDocument::getKnowledgeBaseType, knowledgeBaseType)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        return PageResponse.from(baseMapper.selectPage(documentPage, queryWrapper));
    }

    /**
     * 按文档主键更新处理状态。
     *
     * @param documentId 文档主键
     * @param status     目标处理状态
     */
    @Override
    public void updateStatus(Long documentId, DocumentStatus status) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setStatus(status);
        LambdaUpdateWrapper<KnowledgeDocument> updateWrapper = new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId);
        baseMapper.update(update, updateWrapper);
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
