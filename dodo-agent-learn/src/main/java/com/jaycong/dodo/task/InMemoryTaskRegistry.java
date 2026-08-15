package com.jaycong.dodo.task;

import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryTaskRegistry {

    private final ConcurrentMap<String, TaskEntry> tasks = new ConcurrentHashMap<>();

    public boolean register(String conversationId, Runnable onCancel) {
        return tasks.putIfAbsent(conversationId, new TaskEntry(onCancel)) == null;
    }

    public boolean hasRunningTask(String conversationId) {
        return tasks.containsKey(conversationId);
    }

    public void attach(String conversationId, Disposable subscription) {
        TaskEntry entry = tasks.get(conversationId);
        if (entry == null) {
            subscription.dispose();
            return;
        }
        entry.attach(subscription);
    }

    public boolean cancel(String conversationId) {
        TaskEntry entry = tasks.remove(conversationId);
        if (entry == null) {
            return false;
        }
        entry.cancel();
        return true;
    }

    public void complete(String conversationId) {
        TaskEntry entry = tasks.remove(conversationId);
        if (entry != null) {
            entry.complete();
        }
    }

    private static final class TaskEntry {
        private final Runnable onCancel;
        private Disposable subscription;
        private boolean closed;

        private TaskEntry(Runnable onCancel) {
            this.onCancel = onCancel;
        }

        private synchronized void attach(Disposable newSubscription) {
            if (closed) {
                newSubscription.dispose();
                return;
            }
            subscription = newSubscription;
        }

        private synchronized void cancel() {
            if (closed) {
                return;
            }
            closed = true;
            if (subscription != null) {
                subscription.dispose();
            }
            onCancel.run();
        }

        private synchronized void complete() {
            closed = true;
        }
    }
}
