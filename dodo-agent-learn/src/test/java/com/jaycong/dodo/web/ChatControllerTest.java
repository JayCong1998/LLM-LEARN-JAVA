package com.jaycong.dodo.web;

import com.jaycong.dodo.agent.ChatStreamPort;
import com.jaycong.dodo.agent.StreamingChatAgent;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@WebFluxTest(ChatController.class)
@Import({StreamingChatAgent.class, InMemoryTaskRegistry.class, ChatControllerTest.FakeModelConfiguration.class})
class ChatControllerTest {

    @Autowired
    private WebTestClient client;

    @Test
    void streamsJsonEventsAsServerSentEvents() {
        client.get()
                .uri("/api/agent/chat/stream?conversationId=c-1&message=hello")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("\"type\":\"text\"")
                        .contains("\"content\":\"model answer\"")
                        .contains("\"type\":\"complete\""));
    }

    @Test
    void rejectsBlankMessagesBeforeCallingTheAgent() {
        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent/chat/stream")
                        .queryParam("conversationId", "c-1")
                        .queryParam("message", " ")
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void reportsWhenThereIsNoTaskToStop() {
        client.post()
                .uri("/api/agent/tasks/no-task/stop")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("{\"stopped\":false}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfiguration {

        @Bean
        ChatStreamPort chatStreamPort() {
            return message -> Flux.just("model answer");
        }
    }
}
