package com.jaycong.know.engine.document;

import com.jaycong.know.engine.rag.splitter.MarkdownHeaderParentTextSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

public class MarkdownHeaderParentTextSplitterTest {

    @Test
    public void testSplitText() {

        MarkdownHeaderParentTextSplitter markdownHeaderParentTextSplitter = new MarkdownHeaderParentTextSplitter(3, false, false, 1000, 100);
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("document/minerU解析后的原始文件.md");
        DocumentParser parser = new TextDocumentParser();
        Document parsedDocument = parser.parse(inputStream);
        List<TextSegment> segments = markdownHeaderParentTextSplitter.split(parsedDocument);

        System.out.println(segments.size());

        for (TextSegment segment : segments) {
            System.out.println(segment.text());
            System.out.println(segment.metadata());
            System.out.println("======");
        }

    }
}
