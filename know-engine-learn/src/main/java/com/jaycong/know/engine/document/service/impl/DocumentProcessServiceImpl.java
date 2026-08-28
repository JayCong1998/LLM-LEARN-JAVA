package com.jaycong.know.engine.document.service.impl;


import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.constant.DocumentStatus;
import com.jaycong.know.engine.document.constant.KnowledgeBaseType;
import com.jaycong.know.engine.document.dto.DocumentSplitParam;
import com.jaycong.know.engine.document.dto.DocumentUploadParam;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.mapper.KnowledgeDocumentMapper;
import com.jaycong.know.engine.document.process.FileProcessService;
import com.jaycong.know.engine.document.process.FileProcessServiceFactory;
import com.jaycong.know.engine.document.service.DocumentProcessService;
import com.jaycong.know.engine.document.service.KnowledgeDocumentService;
import com.jaycong.know.engine.document.util.FileTypeUtil;
import com.jaycong.know.engine.minio.FileStorageService;
import com.jaycong.know.engine.rag.splitter.DocumentSplitterFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    @Autowired
    private FileProcessServiceFactory fileProcessServiceFactory;

    @Autowired
    private KnowledgeDocumentMapper documentMapper;

    @Autowired
    private KnowledgeDocumentService documentService;


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
        this.documentMapper.insert(knowledgeDocument);
//        convertDocument(knowledgeDocument, request.file());
    }

    @Override
    public void manulConvertDocument(Long documentId, String minerUdocUrl) {
        // 1. 根据文档ID查询文档信息
        KnowledgeDocument document = documentService.get(documentId);

        // 2. minerUdocUrl 文件地址，根据url去下载文件并封装成MultipartFile
        MultipartFile file = downloadAsMultipartFile(minerUdocUrl, extractFileNameFromUrl(minerUdocUrl, document));

        // 3. 调用convertDocument方法进行转换处理
        String convertedDocUrl = convertDocument(document, file);

        // 4. 回写转换结果（转换后URL + 状态）
        document.setConvertedDocUrl(convertedDocUrl);
        document.setStatus(DocumentStatus.CONVERTED);
        documentMapper.updateById(document);
    }

    /**
     * 从远程URL下载文件内容并封装为 {@link MultipartFile}。
     *
     * @param url      远程文件下载地址
     * @param fileName 转换后的 MultipartFile 文件名，用于文件类型识别
     * @return 包含远程文件内容的 MultipartFile
     */
    private MultipartFile downloadAsMultipartFile(String url, String fileName) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofMinutes(5))
                .build();
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {
            HttpGet httpGet = new HttpGet(url);
            return httpClient.execute(httpGet, response -> {
                int statusCode = response.getCode();
                if (statusCode >= 400) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载远程文件失败，HTTP状态码: " + statusCode);
                }
                if (response.getEntity() == null) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载远程文件失败，响应内容为空");
                }
                byte[] body = EntityUtils.toByteArray(response.getEntity());
                return new ByteArrayMultipartFile(fileName, body);
            });
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载远程文件失败: " + e.getMessage());
        }
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

    /**
     * 基于字节数组的简单 {@link MultipartFile} 实现，用于将内存中的文件内容传递给文件处理器。
     */
    private record ByteArrayMultipartFile(String fileName, byte[] content) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return content;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            Files.write(dest.toPath(), content);
        }
    }

    public String convertDocument(KnowledgeDocument document, MultipartFile file) {
        String fileName = file.getOriginalFilename();
        String convertedDocUrl;
        FileProcessService fileProcessService = fileProcessServiceFactory.get(FileTypeUtil.getFileType(fileName, file), document.getKnowledgeBaseType());

        if (fileProcessService != null) {
            try {
                convertedDocUrl = fileProcessService.processDocument(document, file.getInputStream());
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件处理器处理转换失败");
            }
        } else {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "找不到文件处理器");
        }
        return convertedDocUrl;
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

            DocumentSplitter splitter = DocumentSplitterFactory.getInstance(documentSplitParam);
            Document doc = Document.from(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            segments = splitter.split(doc);
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }
        System.out.println(segments);
    }
}
