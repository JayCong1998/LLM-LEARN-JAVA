package com.jaycong.dodo.agent;

import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;

class StreamingChatAgentTest {

    @Test
    void convertsModelChunksToTextEventsThenCompletes() {
        StreamingChatAgent agent = new StreamingChatAgent(
                message -> Flux.just("Hel", "lo"),
                new InMemoryTaskRegistry());

        StepVerifier.create(agent.stream("c-1", "hello"))
                .expectNext(AgentStreamEvent.text("Hel"))
                .expectNext(AgentStreamEvent.text("lo"))
                .expectNext(AgentStreamEvent.complete())
                .verifyComplete();
    }

    @Test
    void convertsModelFailureToErrorEventThenCompletes() {
        StreamingChatAgent agent = new StreamingChatAgent(
                message -> Flux.error(new IllegalStateException("model unavailable")),
                new InMemoryTaskRegistry());

        StepVerifier.create(agent.stream("c-1", "hello"))
                .expectNext(AgentStreamEvent.error("model unavailable"))
                .expectNext(AgentStreamEvent.complete())
                .verifyComplete();
    }

    @Test
    void rejectsASecondTaskForTheSameConversation() {
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
        registry.register("c-1", () -> { });
        StreamingChatAgent agent = new StreamingChatAgent(
                message -> Flux.just("unused"),
                registry);

        StepVerifier.create(agent.stream("c-1", "hello"))
                .expectNext(AgentStreamEvent.error("conversation is already running"))
                .expectNext(AgentStreamEvent.complete())
                .verifyComplete();
    }

    @Test
    void cancellingTheTaskStopsTheModelAndClosesTheEventStream() {
        Sinks.Many<String> modelOutput = Sinks.many().unicast().onBackpressureBuffer();
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
        StreamingChatAgent agent = new StreamingChatAgent(
                message -> modelOutput.asFlux(),
                registry);

        StepVerifier.create(agent.stream("c-1", "hello"))
                .then(() -> modelOutput.tryEmitNext("first"))
                .expectNext(AgentStreamEvent.text("first"))
                .then(() -> registry.cancel("c-1"))
                .expectNext(AgentStreamEvent.error("request cancelled"))
                .expectNext(AgentStreamEvent.complete())
                .expectComplete()
                .verify(Duration.ofSeconds(1));
    }
}
