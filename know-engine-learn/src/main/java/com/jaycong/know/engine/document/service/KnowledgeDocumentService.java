package com.jaycong.know.engine.document.service;

import com.jaycong.know.engine.common.api.PageResponse;
import com.jaycong.know.engine.document.constant.DocumentStatus;
import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.dto.DocumentUploadParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;

import java.util.List;

/**
 * 知识文档应用服务定义。
 */
public interface KnowledgeDocumentService {

    /**
     * 分页查询未删除的知识文档，并按指定条件筛选。
     *
     * @param current           当前页码，从 1 开始
     * @param size              每页记录数
     * @param docTitle          可选的文档标题模糊查询条件
     * @param status            可选的文档状态精确查询条件
     * @param knowledgeBaseType 可选的知识库类型精确查询条件
     * @return 包含文档记录和分页信息的查询结果
     */
    PageResponse<KnowledgeDocument> page(long current, long size, String docTitle, String status,
                                         String knowledgeBaseType);

    /**
     * 创建仅包含基础信息、尚未切片的知识文档。
     *
     * @param documentRequest 文档创建请求
     * @return 已创建的知识文档
     * @throws IllegalArgumentException 文档标题为空时抛出
     */
    KnowledgeDocument create(DocumentRequest documentRequest);

    /**
     * 根据主键查询未删除的知识文档。
     *
     * @param documentId 文档主键
     * @return 文档详情
     * @throws com.jaycong.know.engine.common.error.BusinessException 文档不存在或已删除时抛出
     */
    KnowledgeDocument get(Long documentId);

    /**
     * 查询全部未删除的知识文档。
     *
     * @return 文档列表
     */
    List<KnowledgeDocument> list();


    /**
     * 更新知识文档基础信息，不改变文档处理状态。
     *
     * @param documentId      文档主键
     * @param documentRequest 文档更新请求
     * @return 更新后的知识文档
     */
    KnowledgeDocument update(Long documentId, DocumentRequest documentRequest);

    /**
     * 更新状态
     *
     * @param documentId
     * @param status
     */
    void updateStatus(Long documentId, DocumentStatus status);

    /**
     * 软删除指定知识文档。
     *
     * @param documentId 文档主键
     */
    void delete(Long documentId);


}
