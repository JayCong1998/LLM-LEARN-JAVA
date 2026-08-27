package com.jaycong.know.engine.document.service;


import com.jaycong.know.engine.document.dto.DocumentSplitParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;

/**
 * @author pyc
 * @since 2026-08-27 21:59
 */
public interface DocumentProcessService {

    void split(KnowledgeDocument document, DocumentSplitParam documentSplitParam);

}
