package com.jaycong.know.engine.document.service;

import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.dto.FileUploadRequest;
import com.jaycong.know.engine.document.dto.TextUploadRequest;
import com.jaycong.know.engine.document.dto.UploadResult;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;

import java.util.List;

/**
 * 知识文档应用服务定义。
 */
public interface DocumentService {
    /**
     * 上传纯文本，创建知识文档并完成初始切片。
     *
     * @param textUploadRequest 纯文本上传请求
     * @return 新建文档主键及生成的片段数量
     * @throws IllegalArgumentException 标题、正文或切片大小不符合要求时抛出
     */
    UploadResult upload(TextUploadRequest textUploadRequest);

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
     * @param documentId 文档主键
     * @param documentRequest 文档更新请求
     * @return 更新后的知识文档
     */
    KnowledgeDocument update(Long documentId, DocumentRequest documentRequest);

    /**
     * 软删除指定知识文档。
     *
     * @param documentId 文档主键
     */
    void delete(Long documentId);

    /**
     * 处理上传文件请求，创建知识文档并完成必要的持久化动作。
     *
     * @param request 文件上传请求参数
     */
    void uploadFile(FileUploadRequest request);

}
