package com.jaycong.know.engine.document.service;

import com.jaycong.know.engine.common.error.BusinessException;
import com.jaycong.know.engine.common.error.ErrorCode;
import com.jaycong.know.engine.document.dto.TextUploadRequest;
import com.jaycong.know.engine.document.entity.KnowledgeDocument;
import com.jaycong.know.engine.document.entity.KnowledgeSegment;
import com.jaycong.know.engine.document.mapper.KnowledgeDocumentMapper;
import com.jaycong.know.engine.document.mapper.KnowledgeSegmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    @Mock
    private KnowledgeSegmentMapper segmentMapper;

    @InjectMocks
    private DocumentServiceImpl documentService;

    @Test
    void uploadSplitsTextAndMarksDocumentChunked() {
        when(documentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, KnowledgeDocument.class).setDocId(10L);
            return 1;
        });

        TextUploadRequest request = new TextUploadRequest();
        request.setTitle("测试文档");
        request.setContent("abcdefghi");
        request.setChunkSize(4);

        documentService.upload(request);

        ArgumentCaptor<KnowledgeSegment> segments = ArgumentCaptor.forClass(KnowledgeSegment.class);
        verify(segmentMapper, org.mockito.Mockito.times(3)).insert(segments.capture());
        verify(documentMapper).updateById(any(KnowledgeDocument.class));
        assertEquals(List.of("abcd", "efgh", "i"),
                segments.getAllValues().stream().map(KnowledgeSegment::getText).toList());
        assertEquals(List.of(1, 2, 3),
                segments.getAllValues().stream().map(KnowledgeSegment::getChunkOrder).toList());
    }

    @Test
    void uploadRejectsBlankContent() {
        TextUploadRequest request = new TextUploadRequest();
        request.setTitle("测试文档");
        request.setContent("   ");

        assertThrows(IllegalArgumentException.class, () -> documentService.upload(request));
    }

    @Test
    void getMissingDocumentThrowsResourceNotFoundBusinessException() {
        when(documentMapper.selectById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> documentService.get(99L));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        assertEquals("知识文档不存在", exception.getMessage());
    }
}
