package com.jaycong.dodo.agent;

import com.jaycong.dodo.task.InMemoryTaskRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;

public class StreamingChatAgent {

    private final ChatStreamPort model;
    private final InMemoryTaskRegistry tasks;

    public StreamingChatAgent(ChatStreamPort model, InMemoryTaskRegistry tasks) {
        this.model = model;
        this.tasks = tasks;
    }

    public Flux<AgentStreamEvent> stream(String conversationId, String message) {
        return Flux.defer(() -> {
            Sinks.Many<AgentStreamEvent> output = Sinks.many()
                    .unicast()
                    .onBackpressureBuffer();
            AtomicBoolean finished = new AtomicBoolean();

            Runnable onCancel = () -> {
                if (finished.compareAndSet(false, true)) {
                    output.tryEmitNext(AgentStreamEvent.error("request cancelled"));
                    output.tryEmitNext(AgentStreamEvent.complete());
                    output.tryEmitComplete();
                }
            };

            if (!tasks.register(conversationId, onCancel)) {
                return Flux.just(
                        AgentStreamEvent.error("conversation is already running"),
                        AgentStreamEvent.complete());
            }

            var subscription = model.stream(message).subscribe(
                    chunk -> {
                        if (!finished.get()) {
                            output.tryEmitNext(AgentStreamEvent.text(chunk));
                        }
                    },
                    error -> finishWithError(conversationId, output, finished, error),
                    () -> finishSuccessfully(conversationId, output, finished));
            tasks.attach(conversationId, subscription);

            return output.asFlux().doFinally(signal -> {
                if (signal == SignalType.CANCEL) {
                    tasks.cancel(conversationId);
                } else {
                    tasks.complete(conversationId);
                }
            });
        });
    }

    private void finishSuccessfully(
            String conversationId,
            Sinks.Many<AgentStreamEvent> output,
            AtomicBoolean finished) {
        if (finished.compareAndSet(false, true)) {
            tasks.complete(conversationId);
            output.tryEmitNext(AgentStreamEvent.complete());
            output.tryEmitComplete();
        }
    }

    private void finishWithError(
            String conversationId,
            Sinks.Many<AgentStreamEvent> output,
            AtomicBoolean finished,
            Throwable error) {
        if (finished.compareAndSet(false, true)) {
            tasks.complete(conversationId);
            output.tryEmitNext(AgentStreamEvent.error(error.getMessage()));
            output.tryEmitNext(AgentStreamEvent.complete());
            output.tryEmitComplete();
        }
    }
}
