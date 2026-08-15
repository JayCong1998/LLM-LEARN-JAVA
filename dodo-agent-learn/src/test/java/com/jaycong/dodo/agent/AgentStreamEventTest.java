package com.jaycong.dodo.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamEventTest {

    @Test
    void createsStableEvents() {
        assertThat(AgentStreamEvent.text("hello"))
                .isEqualTo(new AgentStreamEvent("text", "hello"));
        assertThat(AgentStreamEvent.error("failed"))
                .isEqualTo(new AgentStreamEvent("error", "failed"));
        assertThat(AgentStreamEvent.complete())
                .isEqualTo(new AgentStreamEvent("complete", ""));
    }
}
