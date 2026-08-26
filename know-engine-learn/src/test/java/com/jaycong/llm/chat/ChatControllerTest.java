package com.jaycong.llm.chat;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @InjectMocks
    private ChatController chatController;

    @Test
    void chatReturnsTheModelReply() {
        when(chatModel.chat("你好")).thenReturn("你好，我是测试助手。");

        assertEquals("你好，我是测试助手。", chatController.chat("你好"));
    }

    @Test
    void streamDelegatesPartialResponsesToAnSseEmitter() {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("你好，");
            handler.onPartialResponse("我是测试助手。");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("完成")).build());
            return null;
        }).when(streamingChatModel).chat(eq("你好"), any(StreamingChatResponseHandler.class));

        SseEmitter emitter = chatController.stream("你好");

        assertNotNull(emitter);
        verify(streamingChatModel).chat(eq("你好"), any(StreamingChatResponseHandler.class));
    }
}
