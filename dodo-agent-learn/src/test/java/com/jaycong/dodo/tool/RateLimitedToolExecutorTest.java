package com.jaycong.dodo.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitedToolExecutorTest {

    @Test
    void returnsStableObservationWithoutCallingDelegateWhenLimitIsExhausted() {
        AtomicInteger delegateCalls = new AtomicInteger();
        ToolExecutionPort delegate = (toolName, arguments) -> {
            delegateCalls.incrementAndGet();
            return "晴朗";
        };
        ToolRateLimiter limiter = new ToolRateLimiter(() -> 0L);
        RateLimitedToolExecutor executor = new RateLimitedToolExecutor(delegate, limiter);
        ToolExecutionContext context = new ToolExecutionContext("conversation-1", "weather", "tool-call-1");

        for (int call = 0; call < 10; call++) {
            executor.execute(context, "{}");
        }

        assertThat(executor.execute(context, "{}")).isEqualTo("工具调用已限流：weather");
        assertThat(delegateCalls).hasValue(10);
    }
}
