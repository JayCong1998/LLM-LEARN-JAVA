package com.jaycong.know.engine.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.dto.DocumentRequest;
import com.jaycong.know.engine.document.dto.FileUploadRequest;
import com.jaycong.know.engine.document.dto.TextUploadRequest;
import com.jaycong.know.engine.document.dto.UploadResult;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.mapper.KnowledgeDocumentMapper;
import com.jaycong.know.engine.document.mapper.KnowledgeSegmentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识文档的持久化、查询及文本上传切片服务。
 */
@Service
public class DocumentServiceImpl implements DocumentService {
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
    public DocumentServiceImpl(KnowledgeDocumentMapper documentMapper, KnowledgeSegmentMapper segmentMapper) {
        this.documentMapper = documentMapper;
        this.segmentMapper = segmentMapper;
    }

    @Autowired
    private FileStorageService fileStorageService;


    /**
     * 上传纯文本，创建知识文档并按指定大小生成初始片段。
     *
     * @param request 纯文本上传请求
     * @return 新建文档主键及生成的片段数量
     */
    @Override
    @Transactional
    public UploadResult upload(TextUploadRequest request) {
        if (request == null || blank(request.getTitle()) || blank(request.getContent()))
            throw new IllegalArgumentException("title and content must not be blank");
        int size = request.getChunkSize() == null ? DEFAULT_CHUNK_SIZE : request.getChunkSize();
        if (size <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocTitle(request.getTitle());
        doc.setDescription(request.getDescription());
        doc.setStatus("UPLOADED");
        doc.setDeleted(0);
        documentMapper.insert(doc);
        String content = request.getContent();
        int count = 0;
        for (int start = 0; start < content.length(); start += size) {
            KnowledgeSegment segment = new KnowledgeSegment();
            segment.setDocumentId(doc.getId());
            segment.setText(content.substring(start, Math.min(start + size, content.length())));
            segment.setChunkOrder(++count);
            segment.setStatus("STORED");
            segment.setSkipEmbedding(0);
            segment.setDeleted(0);
            segmentMapper.insert(segment);
        }
        doc.setStatus("CHUNKED");
        documentMapper.updateById(doc);
        return new UploadResult(doc.getId(), count);
    }

    /**
     * 创建未切片的知识文档。
     *
     * @param documentRequest 文档创建请求
     * @return 已创建的知识文档
     */
    @Override
    public KnowledgeDocument create(DocumentRequest documentRequest) {
        if (documentRequest == null || blank(documentRequest.getTitle()))
            throw new IllegalArgumentException("title must not be blank");
        KnowledgeDocument document = new KnowledgeDocument();
        apply(document, documentRequest);
        document.setStatus("INIT");
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

    /**
     * 软删除指定知识文档。
     *
     * @param id 文档主键
     */
    @Override
    public void delete(Long id) {
        KnowledgeDocument document = get(id);
        document.setDeleted(1);
        documentMapper.updateById(document);
    }

    /**
     * 处理文件上传请求，将请求参数转换为持久化所需的字段。
     *
     * @param request 文件上传请求参数
     */
    @Override
    public void uploadFile(FileUploadRequest request) {
        try {
            fileStorageService.uploadFile(request.file(), request.file().getOriginalFilename());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 校验文档请求并将可编辑字段复制到文档实体。
     *
     * @param document        待更新的文档实体
     * @param documentRequest 文档创建或更新请求
     */
    private void apply(KnowledgeDocument document, DocumentRequest documentRequest) {
        if (documentRequest == null || blank(documentRequest.getTitle()))
            throw new IllegalArgumentException("title must not be blank");
        document.setDocTitle(documentRequest.getTitle());
        document.setDescription(documentRequest.getDescription());
        document.setKnowledgeBaseType(documentRequest.getKnowledgeBaseType());
        document.setExtension(documentRequest.getExtension());
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
