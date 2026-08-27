package com.jaycong.know.engine.document.service.impl;


import com.jaycong.know.engine.document.dto.DocumentSplitParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.service.DocumentProcessService;
import com.jaycong.know.engine.minio.FileStorageService;
import com.jaycong.know.engine.rag.splitter.DocumentSplitterFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author pyc
 * @since 2026-08-27 21:59
 */
@Service
public class DocumentProcessServiceImpl implements DocumentProcessService {

    @Autowired
    private FileStorageService fileStorageService;


    @Override
    public void split(KnowledgeDocument document, DocumentSplitParam documentSplitParam) {
        // 2. 从MinIO下载文件内容（从版本表获取转换后的文档URL）
        String convertedDocUrl = document.getConvertedDocUrl();
        String objectName = fileStorageService.extractObjectNameFromUrl(convertedDocUrl);
        Assert.notNull(objectName, "无法解析文档URL");

        List<KnowledgeSegment> knowledgeSegments = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        try (InputStream inputStream = fileStorageService.downloadFile(objectName)) {

            DocumentSplitter splitter = DocumentSplitterFactory.getInstance(documentSplitParam);
            Document doc = Document.from(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            segments = splitter.split(doc);
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }
        System.out.println(segments);
    }
}
