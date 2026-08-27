package com.jaycong.llm.chat;

import com.jaycong.know.engine.ai.controller.ChatController;
import com.jaycong.know.engine.ai.aiservice.DemoChatService;
import com.jaycong.know.engine.common.api.ApiResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private StreamingChatModel streamingChatModel;

    @Mock
    private DemoChatService demoChatService;

    @InjectMocks
    private ChatController chatController;

    @Test
    void chatReturnsTheModelReply() {
        when(chatModel.chat("你好")).thenReturn("你好，我是测试助手。");

        ApiResponse<String> response = chatController.chat("你好");

        assertEquals(0, response.getCode());
        assertEquals("你好，我是测试助手。", response.getData());
    }

    @Test
    void streamDeclaresUtf8ServerSentEvents() throws NoSuchMethodException {
        GetMapping mapping = ChatController.class
                .getDeclaredMethod("stream", String.class)
                .getAnnotation(GetMapping.class);

        assertArrayEquals(new String[]{"text/event-stream;charset=UTF-8"}, mapping.produces());
    }

}
