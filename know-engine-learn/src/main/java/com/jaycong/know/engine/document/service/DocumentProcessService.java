package com.jaycong.know.engine.document.service;


import com.jaycong.know.engine.document.dto.DocumentSplitParam;
import com.jaycong.know.engine.document.dto.DocumentUploadParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;

/**
 * @author pyc
 * @since 2026-08-27 21:59
 */
public interface DocumentProcessService {


    /**
     * 处理上传文件请求，创建知识文档并完成必要的持久化动作。
     *
     * @param request 文件上传请求参数
     */
    void uploadFile(DocumentUploadParam request);

    void manulConvertDocument(Long documentId, String minerUdocUrl) throws Exception;

    void split(KnowledgeDocument document, DocumentSplitParam documentSplitParam);

}
