package com.jaycong.dodo.task;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskRegistryTest {

    @Test
    void allowsOnlyOneTaskPerConversation() {
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();

        assertThat(registry.register("c-1", () -> { })).isTrue();
        assertThat(registry.register("c-1", () -> { })).isFalse();
        assertThat(registry.hasRunningTask("c-1")).isTrue();
    }

    @Test
    void cancelDisposesSubscriptionClosesOutputAndRemovesTask() {
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
        AtomicBoolean outputClosed = new AtomicBoolean();
        Disposable subscription = Disposables.single();
        registry.register("c-1", () -> outputClosed.set(true));
        registry.attach("c-1", subscription);

        assertThat(registry.cancel("c-1")).isTrue();
        assertThat(subscription.isDisposed()).isTrue();
        assertThat(outputClosed).isTrue();
        assertThat(registry.hasRunningTask("c-1")).isFalse();
    }

    @Test
    void completeRemovesTaskWithoutCallingCancellationCallback() {
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
        AtomicBoolean outputClosedByCancellation = new AtomicBoolean();
        registry.register("c-1", () -> outputClosedByCancellation.set(true));

        registry.complete("c-1");

        assertThat(registry.hasRunningTask("c-1")).isFalse();
        assertThat(outputClosedByCancellation).isFalse();
    }
}
