package com.jaycong.dodo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.memory.InMemoryConversationMemory;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

class ManualReactStreamAgentTest {

    @Test
    void usesTheReactDecisionAsTheFinalAnswerWithoutASecondModelStream() {
        ConversationMemory memory = new InMemoryConversationMemory();
        ReactModelPort model = (messages, toolsEnabled) -> new AssistantMessage("同步探测完成");
        ManualReactStreamAgent agent = new ManualReactStreamAgent(model, new AgentToolRegistry(List.of()), new InMemoryTaskRegistry(), memory, run -> memory.append(run.conversationId(), new com.jaycong.dodo.memory.ConversationTurn(run.question(), run.answer())));

        List<AgentStreamEvent> events = agent.stream("conversation-stream", "问题").collectList().block(Duration.ofSeconds(3));

        assertThat(events).containsExactly(AgentStreamEvent.text("同步探测完成"), AgentStreamEvent.complete());
        assertThat(memory.get("conversation-stream")).singleElement().satisfies(turn -> assertThat(turn.assistantContent()).isEqualTo("同步探测完成"));
    }
}
