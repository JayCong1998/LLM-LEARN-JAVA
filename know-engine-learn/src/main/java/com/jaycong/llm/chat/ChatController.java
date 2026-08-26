package com.jaycong.llm.chat;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;

    public ChatController(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    @GetMapping
    public String chat(@RequestParam String message) {
        return chatModel.chat(message);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter();
        streamingChatModel.chat(message, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    emitter.send(partialResponse);
                } catch (IOException exception) {
                    emitter.completeWithError(exception);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }
}
