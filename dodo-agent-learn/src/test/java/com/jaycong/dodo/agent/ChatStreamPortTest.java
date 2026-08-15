package com.jaycong.dodo.agent;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ChatStreamPortTest {

    @Test
    void exposesModelOutputAsTextChunks() {
        ChatStreamPort port = message -> Flux.just(message, "!");

        StepVerifier.create(port.stream("hello"))
                .expectNext("hello", "!")
                .verifyComplete();
    }
}
