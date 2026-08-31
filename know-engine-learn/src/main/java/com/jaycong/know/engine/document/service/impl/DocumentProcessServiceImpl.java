package com.jaycong.know.engine.document.service.impl;


import com.alibaba.fastjson2.JSON;
import com.google.common.base.Stopwatch;
import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.constant.DocumentStatus;
import com.jaycong.know.engine.document.constant.KnowledgeBaseType;
import com.jaycong.know.engine.document.constant.SegmentStatus;
import com.jaycong.know.engine.document.dto.DocumentSplitParam;
import com.jaycong.know.engine.document.dto.DocumentUploadParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.process.FileProcessService;
import com.jaycong.know.engine.document.process.FileProcessServiceFactory;
import com.jaycong.know.engine.document.service.DocumentProcessService;
import com.jaycong.know.engine.document.service.KnowledgeDocumentService;
import com.jaycong.know.engine.document.service.KnowledgeSegmentService;
import com.jaycong.know.engine.document.util.FileTypeUtil;
import com.jaycong.know.engine.minio.FileStorageService;
import com.jaycong.know.engine.rag.constant.MetadataKeyConstant;
import com.jaycong.know.engine.rag.splitter.MarkdownHeaderParentTextSplitter;
import com.jaycong.know.engine.rag.store.VectorStoreService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author pyc
 * @since 2026-08-27 21:59
 */
@Slf4j
@Service
public class DocumentProcessServiceImpl implements DocumentProcessService {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileProcessServiceFactory fileProcessServiceFactory;

    @Autowired
    private KnowledgeDocumentService documentService;

    @Autowired
    private KnowledgeSegmentService segmentService;

    @Autowired
    private VectorStoreService vectorStoreService;


    @Override
    public void uploadFile(DocumentUploadParam request) {
        KnowledgeDocument knowledgeDocument = new KnowledgeDocument();
        knowledgeDocument.setDocTitle(request.title());
        knowledgeDocument.setDescription(request.description());
        knowledgeDocument.setStatus(DocumentStatus.INIT);
        knowledgeDocument.setKnowledgeBaseType(KnowledgeBaseType.valueOf(request.knowledgeBaseType()));
        try {
            //上传文档获取文件路径
            String fileUrl = fileStorageService.uploadFile(request.file(), request.file().getOriginalFilename());
            knowledgeDocument.setDocUrl(fileUrl);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.documentService.save(knowledgeDocument);
//        convertDocument(knowledgeDocument, request.file());
    }

    @Override
    public void manulConvertDocument(Long documentId, String minerUdocUrl) throws Exception {
        // 1. 根据文档ID查询文档信息
        KnowledgeDocument document = documentService.getDocumentById(documentId);
        // 2. 从 MinIO 下载文件流
        String objectName = fileStorageService.extractObjectNameFromUrl(minerUdocUrl);
        String fileName = extractFileNameFromUrl(minerUdocUrl, document);
        String convertedDocUrl;
        try (InputStream inputStream = fileStorageService.downloadFile(objectName)) {
            // 3. 调用 convertDocument 进行转换处理（直接传 InputStream，避免 MultipartFile 中间层）
            convertedDocUrl = convertDocument(document, fileName, inputStream);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载远程文件失败: " + e.getMessage());
        }

        // 4. 回写转换结果（转换后URL + 状态）
        document.setConvertedDocUrl(convertedDocUrl);
        document.setStatus(DocumentStatus.CONVERTED);
        documentService.updateById(document);
    }

    /**
     * 从URL中提取文件名，若无法解析则基于文档标题生成默认文件名。
     *
     * @param url      远程文件URL
     * @param document 文档实体，用于生成默认文件名
     * @return 提取或生成的文件名
     */
    private String extractFileNameFromUrl(String url, KnowledgeDocument document) {
        String path = URI.create(url).getPath();
        if (path != null && !path.isEmpty()) {
            int slashIndex = path.lastIndexOf('/');
            String name = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
            if (!name.isEmpty()) {
                return name;
            }
        }
        String title = document.getDocTitle();
        return (title == null || title.isEmpty() ? "manual_convert_" + document.getId() : title) + ".md";
    }

    public String convertDocument(KnowledgeDocument document, String fileName, InputStream inputStream) {
        FileProcessService fileProcessService = fileProcessServiceFactory.get(
                FileTypeUtil.getFileType(fileName), document.getKnowledgeBaseType());

        if (fileProcessService == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "找不到文件处理器");
        }
        return fileProcessService.processDocument(document, inputStream);
    }

    @Override
    public void split(KnowledgeDocument document, DocumentSplitParam documentSplitParam) {
        // 2. 从MinIO下载文件内容（从版本表获取转换后的文档URL）
        String convertedDocUrl = document.getConvertedDocUrl();
        String objectName = fileStorageService.extractObjectNameFromUrl(convertedDocUrl);
        Assert.notNull(objectName, "无法解析文档URL");

        List<KnowledgeSegment> knowledgeSegments = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        try (InputStream inputStream = fileStorageService.downloadFile(objectName)) {

//            DocumentSplitter splitter = DocumentSplitterFactory.getInstance(documentSplitParam);
            //先默认吧
            DocumentSplitter splitter = new MarkdownHeaderParentTextSplitter(2, false, false, documentSplitParam.chunkSize(), documentSplitParam.overlap());
            Document doc = Document.from(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            segments = splitter.split(doc);
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }
        // 4. 转换为 KnowledgeSegment 并保存
        for (int i = 0; i < segments.size(); i++) {

            TextSegment segment = segments.get(i);
            KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
            knowledgeSegment.setText(segment.text());
            knowledgeSegment.setChunkId(segment.metadata().getString(MetadataKeyConstant.CHUNK_ID));
            Metadata metadata = segment.metadata();
            knowledgeSegment.setMetadata(enrichMetadata(document, metadata));
            knowledgeSegment.setDocumentId(document.getId());
            knowledgeSegment.setChunkOrder(i);

            // 检查是否需要跳过嵌入
            Integer skipEmbedding = metadata.getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
            if (skipEmbedding != null && skipEmbedding == 1) {
                knowledgeSegment.setSkipEmbedding(1);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            } else {
                knowledgeSegment.setSkipEmbedding(0);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            }

            knowledgeSegments.add(knowledgeSegment);
        }

        // 5. 批量保存片段
        Stopwatch stopwatch = Stopwatch.createStarted();
        //这里使用mybatisplus service的批量插入可以配置mysql的批量参数实现性能优化
        boolean saveResult = segmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveResult, "保存知识片段失败");
        log.info("保存知识片段耗时: {}", stopwatch.elapsed().toMillis());

        int segmentCount = knowledgeSegments.size();

        // 6. 更新文档状态为 CHUNKED，并保存分段参数
        documentService.updateStatus(document.getId(), DocumentStatus.CHUNKED);

        // 发送文档已分段事件  todo
//        publishChunkedEvent(document, segmentCount);

        try {
            List<String> strings = vectorStoreService.embedAndStore(knowledgeSegments);

            boolean success = true;
            Assert.isTrue(success, "向量嵌入失败: documentId=" + document.getId());
            log.info("向量嵌入完成，documentId: {}, success: {}", document.getId(), success);
            document.setStatus(DocumentStatus.VECTOR_STORED);
            boolean docResult = documentService.updateById(document);
        } catch (Exception e) {
            log.error("向量嵌入失败，documentId: {}", document.getId(), e);
        }
    }


    /**
     * 填充元数据
     *
     * @param document 文档信息
     * @param metadata 元数据
     * @return
     */
    private static String enrichMetadata(KnowledgeDocument document, Metadata metadata) {
        metadata.put(MetadataKeyConstant.DOC_ID, document.getId());
        metadata.put(MetadataKeyConstant.FILE_NAME, document.getDocTitle());
        Map<String, Object> metadataMap = metadata.toMap();
        return JSON.toJSONString(metadataMap);
    }
}
