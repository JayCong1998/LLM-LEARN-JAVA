package com.jaycong.dodo.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ToolRateLimiterTest {

    @Test
    void limitsEachConversationAndToolIndependentlyToTenCallsPerMinute() {
        AtomicLong nowMillis = new AtomicLong(0L);
        ToolRateLimiter limiter = new ToolRateLimiter(nowMillis::get);

        for (int call = 0; call < 10; call++) {
            assertThat(limiter.tryAcquire("conversation-1", "weather")).isTrue();
        }

        assertThat(limiter.tryAcquire("conversation-1", "weather")).isFalse();
        assertThat(limiter.tryAcquire("conversation-1", "calculator")).isTrue();
        assertThat(limiter.tryAcquire("conversation-2", "weather")).isTrue();
        nowMillis.set(60_000L);
        assertThat(limiter.tryAcquire("conversation-1", "weather")).isTrue();
    }
}
