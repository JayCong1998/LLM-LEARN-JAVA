package com.jaycong.dodo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SpringAiFinalAnswerStreamAdapterTest {

    @Test
    void forwardsCompleteMessagesAndFiltersEmptyModelFragments() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("streaming test only");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                capturedPrompt.set(prompt);
                return Flux.just(response("第"), response(""), response("二"));
            }
        };
        SpringAiFinalAnswerStreamAdapter adapter = new SpringAiFinalAnswerStreamAdapter(model);
        List<Message> messages = List.of(new UserMessage("问题"), new AssistantMessage("已有观察"));

        StepVerifier.create(adapter.stream(messages))
                .expectNext("第", "二")
                .verifyComplete();
        assertThat(capturedPrompt.get().getInstructions()).containsExactlyElementsOf(messages);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
