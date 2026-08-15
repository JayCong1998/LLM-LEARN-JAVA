package com.jaycong.dodo.web;

import com.jaycong.dodo.agent.AgentStreamEvent;
import com.jaycong.dodo.agent.StreamingChatAgent;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/agent")
public class ChatController {

    private final StreamingChatAgent agent;
    private final InMemoryTaskRegistry tasks;

    public ChatController(StreamingChatAgent agent, InMemoryTaskRegistry tasks) {
        this.agent = agent;
        this.tasks = tasks;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> stream(
            @RequestParam String conversationId,
            @RequestParam String message) {
        if (conversationId.isBlank() || message.isBlank()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "conversationId and message must not be blank");
        }
        return agent.stream(conversationId, message)
                .map(event -> ServerSentEvent.builder(event)
                        .event(event.type())
                        .build());
    }

    @PostMapping("/tasks/{conversationId}/stop")
    public StopResponse stop(@PathVariable String conversationId) {
        return new StopResponse(tasks.cancel(conversationId));
    }

    public record StopResponse(boolean stopped) {
    }
}
