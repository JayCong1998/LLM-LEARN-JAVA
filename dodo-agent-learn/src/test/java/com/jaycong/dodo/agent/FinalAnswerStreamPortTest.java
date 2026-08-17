package com.jaycong.dodo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FinalAnswerStreamPortTest {

    @Test
    void streamsFinalAnswerFragmentsFromCompleteMessageContext() {
        FinalAnswerStreamPort port = messages -> Flux.just("第", "一段");

        StepVerifier.create(port.stream(List.of(new UserMessage("问题"))))
                .expectNext("第", "一段")
                .verifyComplete();
        assertThat(port).isNotNull();
    }
}
