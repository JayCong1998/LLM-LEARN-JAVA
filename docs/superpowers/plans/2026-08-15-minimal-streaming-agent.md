# Minimal Streaming Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Build a runnable Spring WebFlux service that streams model text as SSE and cancels a running response by conversationId.

**Architecture:** ChatController handles HTTP. StreamingChatAgent owns the model subscription and stream events. ChatStreamPort hides Spring AI. InMemoryTaskRegistry provides duplicate protection, cancellation and cleanup.

**Tech Stack:** Java 21, Spring Boot 3.5.6, WebFlux, Spring AI, Reactor, JUnit 5.

---

## Files

- Modify: dodo-agent-learn/pom.xml
- Modify: dodo-agent-learn/src/main/resources/application.yml
- Delete: dodo-agent-learn/src/main/java/com/jaycong/dodo/Main.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/DodoAgentLearnApplication.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ChatStreamPort.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiChatStreamAdapter.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/task/InMemoryTaskRegistry.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/StreamingChatAgent.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java
- Modify: dodo-agent-learn/src/main/resources/static/index.html
- Modify: dodo-agent-learn/src/main/resources/static/js/app.js
- Modify: dodo-agent-learn/src/main/resources/static/css/style.css
- Create: tests under dodo-agent-learn/src/test/java/com/jaycong/dodo

## Task 1: Establish a focused executable module

**Files:**

- Modify: dodo-agent-learn/pom.xml
- Modify: dodo-agent-learn/src/main/resources/application.yml
- Delete: dodo-agent-learn/src/main/java/com/jaycong/dodo/Main.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/DodoAgentLearnApplication.java
- Test: dodo-agent-learn/src/test/java/com/jaycong/dodo/DodoAgentLearnApplicationTest.java

- [ ] **Step 1: Write the failing application-context test**

~~~java
package com.jaycong.dodo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class DodoAgentLearnApplicationTest {
    @Test void contextLoads() { }
}
~~~

- [ ] **Step 2: Run the failing test**

Run: mvn -pl dodo-agent-learn test -Dtest=DodoAgentLearnApplicationTest

Expected: FAIL because the project has no Spring Boot entry class.

- [ ] **Step 3: Replace the module dependencies**

Replace the dependencies block with:

~~~xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
~~~

Keep only compiler and source-encoding properties. This removes JDBC, Redis, MinIO, RAG, MCP and PPT dependencies from the first executable.

- [ ] **Step 4: Replace copied configuration with safe configuration**

~~~yaml
spring:
  application:
    name: dodo-agent-learn
  ai:
    openai:
      api-key: ${DODO_AGENT_OPENAI_API_KEY:}
      base-url: ${DODO_AGENT_OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${DODO_AGENT_CHAT_MODEL:gpt-4o-mini}
          temperature: 0.2
server:
  port: ${SERVER_PORT:8080}
~~~

- [ ] **Step 5: Replace the IntelliJ sample entry point**

~~~java
package com.jaycong.dodo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DodoAgentLearnApplication {
    public static void main(String[] args) {
        SpringApplication.run(DodoAgentLearnApplication.class, args);
    }
}
~~~

- [ ] **Step 6: Verify and commit**

Run: mvn -pl dodo-agent-learn test -Dtest=DodoAgentLearnApplicationTest

Expected: PASS.

~~~bash
git add dodo-agent-learn/pom.xml dodo-agent-learn/src/main/resources/application.yml dodo-agent-learn/src/main/java/com/jaycong/dodo/DodoAgentLearnApplication.java dodo-agent-learn/src/test/java/com/jaycong/dodo/DodoAgentLearnApplicationTest.java
git rm -- dodo-agent-learn/src/main/java/com/jaycong/dodo/Main.java
git commit -m "build: create minimal agent application"
~~~

## Task 2: Define stream events and the model boundary

**Files:**

- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ChatStreamPort.java
- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiChatStreamAdapter.java
- Test: dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java

- [ ] **Step 1: Write the failing protocol test**

~~~java
package com.jaycong.dodo.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamEventTest {
    @Test void createsStableEvents() {
        assertThat(AgentStreamEvent.text("hello")).isEqualTo(new AgentStreamEvent("text", "hello"));
        assertThat(AgentStreamEvent.error("failed")).isEqualTo(new AgentStreamEvent("error", "failed"));
        assertThat(AgentStreamEvent.complete()).isEqualTo(new AgentStreamEvent("complete", ""));
    }
}
~~~

- [ ] **Step 2: Run the failing test**

Run: mvn -pl dodo-agent-learn test -Dtest=AgentStreamEventTest

Expected: FAIL because AgentStreamEvent is undefined.

- [ ] **Step 3: Implement the protocol and model port**

~~~java
package com.jaycong.dodo.agent;

public record AgentStreamEvent(String type, String content) {
    public static AgentStreamEvent text(String content) { return new AgentStreamEvent("text", content); }
    public static AgentStreamEvent error(String content) { return new AgentStreamEvent("error", content); }
    public static AgentStreamEvent complete() { return new AgentStreamEvent("complete", ""); }
}
~~~

~~~java
package com.jaycong.dodo.agent;

import reactor.core.publisher.Flux;

public interface ChatStreamPort {
    Flux<String> stream(String message);
}
~~~

- [ ] **Step 4: Implement the production adapter**

~~~java
package com.jaycong.dodo.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class SpringAiChatStreamAdapter implements ChatStreamPort {
    private final ChatClient client;

    public SpringAiChatStreamAdapter(ChatModel model) {
        this.client = ChatClient.builder(model).build();
    }

    @Override
    public Flux<String> stream(String message) {
        return client.prompt().user(message).stream().content();
    }
}
~~~

- [ ] **Step 5: Verify and commit**

Run: mvn -pl dodo-agent-learn test -Dtest=AgentStreamEventTest

Expected: PASS.

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java
git commit -m "feat: define agent stream protocol"
~~~

## Task 3: Build task cancellation

**Files:**

- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/task/InMemoryTaskRegistry.java
- Test: dodo-agent-learn/src/test/java/com/jaycong/dodo/task/InMemoryTaskRegistryTest.java

- [ ] **Step 1: Write the failing registry tests**

~~~java
package com.jaycong.dodo.task;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskRegistryTest {
    @Test void allowsOneTaskPerConversation() {
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
        assertThat(registry.register("c-1")).isTrue();
        assertThat(registry.register("c-1")).isFalse();
    }

    @Test void cancelDisposesAndRemovesTask() {
        InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
        Disposable subscription = Disposables.single();
        registry.register("c-1");
        registry.attach("c-1", subscription);
        assertThat(registry.cancel("c-1")).isTrue();
        assertThat(subscription.isDisposed()).isTrue();
        assertThat(registry.hasRunningTask("c-1")).isFalse();
    }
}
~~~

- [ ] **Step 2: Run the failing test**

Run: mvn -pl dodo-agent-learn test -Dtest=InMemoryTaskRegistryTest

Expected: FAIL because InMemoryTaskRegistry is undefined.

- [ ] **Step 3: Implement the registry**

~~~java
package com.jaycong.dodo.task;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

@Component
public class InMemoryTaskRegistry {
    private static final Disposable PENDING = () -> { };
    private final ConcurrentMap<String, Disposable> tasks = new ConcurrentHashMap<>();

    public boolean register(String id) {
        return tasks.putIfAbsent(id, PENDING) == null;
    }

    public void attach(String id, Disposable subscription) {
        if (!tasks.replace(id, PENDING, subscription)) subscription.dispose();
    }

    public boolean cancel(String id) {
        Disposable subscription = tasks.remove(id);
        if (subscription == null) return false;
        subscription.dispose();
        return true;
    }

    public void complete(String id) {
        tasks.remove(id);
    }

    public boolean hasRunningTask(String id) {
        return tasks.containsKey(id);
    }
}
~~~

- [ ] **Step 4: Add cleanup coverage**

~~~java
@Test void completeRemovesWithoutDisposing() {
    InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
    Disposable subscription = Disposables.single();
    registry.register("c-1");
    registry.attach("c-1", subscription);
    registry.complete("c-1");
    assertThat(subscription.isDisposed()).isFalse();
    assertThat(registry.hasRunningTask("c-1")).isFalse();
}
~~~

- [ ] **Step 5: Verify and commit**

Run: mvn -pl dodo-agent-learn test -Dtest=InMemoryTaskRegistryTest

Expected: PASS with three tests.

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/task/InMemoryTaskRegistry.java dodo-agent-learn/src/test/java/com/jaycong/dodo/task/InMemoryTaskRegistryTest.java
git commit -m "feat: manage in-memory agent tasks"
~~~

## Task 4: Implement the streaming agent

**Files:**

- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/StreamingChatAgent.java
- Test: dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/StreamingChatAgentTest.java

- [ ] **Step 1: Write failing agent tests**

~~~java
package com.jaycong.dodo.agent;

import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class StreamingChatAgentTest {
    @Test void convertsChunksToTextThenComplete() {
        StreamingChatAgent agent = new StreamingChatAgent(message -> Flux.just("Hel", "lo"), new InMemoryTaskRegistry());
        StepVerifier.create(agent.stream("c-1", "hi"))
                .expectNext(AgentStreamEvent.text("Hel"))
                .expectNext(AgentStreamEvent.text("lo"))
                .expectNext(AgentStreamEvent.complete())
                .verifyComplete();
    }

    @Test void convertsFailureToErrorThenComplete() {
        StreamingChatAgent agent = new StreamingChatAgent(
                message -> Flux.error(new IllegalStateException("model unavailable")), new InMemoryTaskRegistry());
        StepVerifier.create(agent.stream("c-1", "hi"))
                .expectNext(AgentStreamEvent.error("model unavailable"))
                .expectNext(AgentStreamEvent.complete())
                .verifyComplete();
    }
}
~~~

- [ ] **Step 2: Run the failing test**

Run: mvn -pl dodo-agent-learn test -Dtest=StreamingChatAgentTest

Expected: FAIL because StreamingChatAgent is undefined.

- [ ] **Step 3: Implement sink orchestration**

~~~java
package com.jaycong.dodo.agent;

import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class StreamingChatAgent {
    private final ChatStreamPort model;
    private final InMemoryTaskRegistry tasks;

    public StreamingChatAgent(ChatStreamPort model, InMemoryTaskRegistry tasks) {
        this.model = model;
        this.tasks = tasks;
    }

    public Flux<AgentStreamEvent> stream(String id, String message) {
        if (!tasks.register(id)) {
            return Flux.just(AgentStreamEvent.error("conversation is already running"), AgentStreamEvent.complete());
        }
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        var subscription = model.stream(message).subscribe(
                chunk -> sink.tryEmitNext(AgentStreamEvent.text(chunk)),
                error -> closeWithError(id, sink, error),
                () -> closeSuccessfully(id, sink));
        tasks.attach(id, subscription);
        return sink.asFlux().doFinally(signal -> tasks.cancel(id));
    }

    private void closeSuccessfully(String id, Sinks.Many<AgentStreamEvent> sink) {
        tasks.complete(id);
        sink.tryEmitNext(AgentStreamEvent.complete());
        sink.tryEmitComplete();
    }

    private void closeWithError(String id, Sinks.Many<AgentStreamEvent> sink, Throwable error) {
        tasks.complete(id);
        sink.tryEmitNext(AgentStreamEvent.error(error.getMessage()));
        sink.tryEmitNext(AgentStreamEvent.complete());
        sink.tryEmitComplete();
    }
}
~~~

- [ ] **Step 4: Add duplicate coverage**

~~~java
@Test void emitsErrorForADuplicateConversation() {
    InMemoryTaskRegistry registry = new InMemoryTaskRegistry();
    registry.register("c-1");
    StreamingChatAgent agent = new StreamingChatAgent(message -> Flux.just("unused"), registry);
    StepVerifier.create(agent.stream("c-1", "hi"))
            .expectNext(AgentStreamEvent.error("conversation is already running"))
            .expectNext(AgentStreamEvent.complete())
            .verifyComplete();
}
~~~

- [ ] **Step 5: Verify and commit**

Run: mvn -pl dodo-agent-learn test -Dtest=StreamingChatAgentTest

Expected: PASS with three tests.

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/StreamingChatAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/StreamingChatAgentTest.java
git commit -m "feat: stream single-round agent responses"
~~~

## Task 5: Expose SSE and stop APIs

**Files:**

- Create: dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java
- Test: dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java

- [ ] **Step 1: Write the failing controller test**

~~~java
package com.jaycong.dodo.web;

import com.jaycong.dodo.agent.ChatStreamPort;
import com.jaycong.dodo.agent.StreamingChatAgent;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@WebFluxTest(ChatController.class)
@Import({StreamingChatAgent.class, InMemoryTaskRegistry.class, ChatControllerTest.FakeModel.class})
class ChatControllerTest {
    @org.springframework.beans.factory.annotation.Autowired WebTestClient client;

    @Test void streamsSseJson() {
        client.get().uri("/api/agent/chat/stream?conversationId=c-1&message=hi").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("\\"type\\":\\"text\\"").contains("\\"content\\":\\"hello\\"").contains("\\"type\\":\\"complete\\""));
    }

    @Test void rejectsBlankMessage() {
        client.get().uri("/api/agent/chat/stream?conversationId=c-1&message=%20").exchange()
                .expectStatus().isBadRequest();
    }

    static class FakeModel {
        @Bean ChatStreamPort chatStreamPort() { return message -> Flux.just("hello"); }
    }
}
~~~

- [ ] **Step 2: Run the failing test**

Run: mvn -pl dodo-agent-learn test -Dtest=ChatControllerTest

Expected: FAIL because ChatController is undefined.

- [ ] **Step 3: Implement the controller**

~~~java
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
    public Flux<ServerSentEvent<AgentStreamEvent>> stream(@RequestParam String conversationId, @RequestParam String message) {
        if (conversationId.isBlank() || message.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "conversationId and message must not be blank");
        }
        return agent.stream(conversationId, message)
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }

    @PostMapping("/tasks/{conversationId}/stop")
    public StopResponse stop(@PathVariable String conversationId) {
        return new StopResponse(tasks.cancel(conversationId));
    }

    public record StopResponse(boolean stopped) { }
}
~~~

- [ ] **Step 4: Add stop endpoint coverage**

~~~java
@Test void reportsWhenNoTaskWasStopped() {
    client.post().uri("/api/agent/tasks/no-task/stop").exchange()
            .expectStatus().isOk().expectBody().json("{\\"stopped\\":false}");
}
~~~

- [ ] **Step 5: Verify and commit**

Run: mvn -pl dodo-agent-learn test

Expected: PASS.

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java
git commit -m "feat: expose agent SSE endpoints"
~~~

## Task 6: Add the learning console

**Files:**

- Modify: dodo-agent-learn/src/main/resources/static/index.html
- Modify: dodo-agent-learn/src/main/resources/static/js/app.js
- Modify: dodo-agent-learn/src/main/resources/static/css/style.css

- [ ] **Step 1: Replace the copied multi-agent page**

~~~html
<main class="console">
  <h1>Minimal Streaming Agent</h1>
  <p>Conversation: <code id="conversation-id"></code></p>
  <textarea id="message" rows="4" placeholder="Ask a question"></textarea>
  <p><button id="send">Send</button> <button id="stop" disabled>Stop</button></p>
  <pre id="output" aria-live="polite"></pre>
  <p id="status">Ready</p>
</main>
~~~

- [ ] **Step 2: Replace the JavaScript stream reader**

~~~javascript
const id = crypto.randomUUID();
const message = document.querySelector('#message');
const output = document.querySelector('#output');
const status = document.querySelector('#status');
const send = document.querySelector('#send');
const stop = document.querySelector('#stop');
document.querySelector('#conversation-id').textContent = id;
let controller;

send.onclick = async () => {
  const text = message.value.trim();
  if (!text) return;
  controller = new AbortController();
  send.disabled = true; stop.disabled = false; output.textContent = ''; status.textContent = 'Streaming';
  try {
    const url = new URL('/api/agent/chat/stream', location.origin);
    url.searchParams.set('conversationId', id); url.searchParams.set('message', text);
    const response = await fetch(url, {signal: controller.signal});
    if (!response.ok || !response.body) throw new Error('Request failed: ' + response.status);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const read = await reader.read();
      if (read.done) break;
      buffer += decoder.decode(read.value, {stream: true});
      const frames = buffer.split('\n\n'); buffer = frames.pop();
      for (const frame of frames) {
        const line = frame.split('\n').find(value => value.startsWith('data:'));
        if (!line) continue;
        const event = JSON.parse(line.slice(5));
        if (event.type === 'text') output.textContent += event.content;
        if (event.type === 'error') status.textContent = 'Error: ' + event.content;
        if (event.type === 'complete') status.textContent = 'Complete';
      }
    }
  } catch (error) {
    status.textContent = error.name === 'AbortError' ? 'Stopped' : 'Error: ' + error.message;
  } finally {
    send.disabled = false; stop.disabled = true; controller = undefined;
  }
};

stop.onclick = async () => {
  await fetch('/api/agent/tasks/' + encodeURIComponent(id) + '/stop', {method: 'POST'});
  controller?.abort();
};
~~~

- [ ] **Step 3: Use focused styles**

~~~css
body { margin: 0; font: 16px/1.5 system-ui, sans-serif; background: #f6f8fa; }
.console { max-width: 760px; margin: 48px auto; padding: 24px; background: white; border: 1px solid #d0d7de; border-radius: 12px; }
textarea, pre { box-sizing: border-box; width: 100%; padding: 12px; border: 1px solid #d0d7de; border-radius: 6px; }
pre { min-height: 180px; white-space: pre-wrap; background: #f6f8fa; }
button:disabled { opacity: .55; }
~~~

- [ ] **Step 4: Run and manually verify**

Run: mvn -pl dodo-agent-learn test

Expected: PASS.

In the current PowerShell session, set the three DODO_AGENT_* environment variables to your provider values, then run: `mvn -pl dodo-agent-learn spring-boot:run`.

Open http://localhost:8080. Send a question and observe incremental output. Send another and click Stop before it completes. Expected: no key is written to a tracked file; output stops and status becomes Stopped.

- [ ] **Step 5: Commit the console**

~~~bash
git add dodo-agent-learn/src/main/resources/static/index.html dodo-agent-learn/src/main/resources/static/js/app.js dodo-agent-learn/src/main/resources/static/css/style.css
git commit -m "feat: add minimal streaming agent console"
~~~

## Final verification

- [ ] mvn -pl dodo-agent-learn test passes.
- [ ] Streaming, model-error conversion, invalid request rejection, duplicate prevention and explicit stop are verified.
- [ ] Compare the learning implementation to BaseAgent, WebSearchReactAgent and AgentTaskManager in the reference project before starting the ReAct iteration.
