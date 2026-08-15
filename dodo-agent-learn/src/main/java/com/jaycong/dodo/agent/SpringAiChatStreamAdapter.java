package com.jaycong.dodo.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 将 Spring AI 的 ChatModel 适配为 Agent 需要的最小文本流接口。
 */
@Component
public class SpringAiChatStreamAdapter implements ChatStreamPort {

    private final ChatClient chatClient;

    public SpringAiChatStreamAdapter(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public Flux<String> stream(String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
