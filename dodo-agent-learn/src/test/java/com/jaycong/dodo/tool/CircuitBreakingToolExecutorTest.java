package com.jaycong.dodo.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakingToolExecutorTest {

    @Test
    void skipsDelegateWhileToolCircuitIsOpen() {
        AtomicLong nowMillis = new AtomicLong(0L);
        ToolCircuitBreaker circuitBreaker = new ToolCircuitBreaker(nowMillis::get);
        AtomicInteger delegateCalls = new AtomicInteger();
        ToolExecutionPort delegate = (toolName, arguments) -> {
            delegateCalls.incrementAndGet();
            return "工具执行超时：" + toolName;
        };
        CircuitBreakingToolExecutor executor = new CircuitBreakingToolExecutor(delegate, circuitBreaker);
        ToolExecutionContext context = new ToolExecutionContext("conversation-1", "weather", "tool-call-1");

        executor.execute(context, "{}");
        executor.execute(context, "{}");
        executor.execute(context, "{}");

        assertThat(executor.execute(context, "{}")).isEqualTo("工具已熔断：weather");
        assertThat(delegateCalls).hasValue(3);
    }
}
