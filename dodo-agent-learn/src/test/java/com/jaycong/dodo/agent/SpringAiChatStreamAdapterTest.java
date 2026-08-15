package com.jaycong.dodo.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiChatStreamAdapterTest {

    @Test
    void forwardsTheUserMessageAndExposesModelChunks() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("streaming test only");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                capturedPrompt.set(prompt);
                return Flux.just(response("Hel"), response("lo"));
            }
        };

        SpringAiChatStreamAdapter adapter = new SpringAiChatStreamAdapter(model);

        StepVerifier.create(adapter.stream("hello"))
                .expectNext("Hel", "lo")
                .verifyComplete();
        assertThat(capturedPrompt.get().getUserMessage().getText()).isEqualTo("hello");
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
