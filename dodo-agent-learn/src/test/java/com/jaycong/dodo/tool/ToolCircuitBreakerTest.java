package com.jaycong.dodo.tool; // 将熔断状态机测试放在工具包中以访问包内可控时钟构造器。

import org.junit.jupiter.api.Test; // 引入 JUnit 测试注解。

import java.util.concurrent.atomic.AtomicLong; // 引入可由测试推进的单调时间源。

import static org.assertj.core.api.Assertions.assertThat; // 引入 AssertJ 状态断言。

class ToolCircuitBreakerTest { // 定义每工具连续失败、熔断和半开探测的行为测试。

    @Test
    void opensOnlyFailedToolAfterThreeFinalFailuresAndClosesAfterSuccessfulProbe() { // 验证工具状态隔离且半开成功会清零恢复。
        AtomicLong nowMillis = new AtomicLong(); // 创建测试可控的单调毫秒时钟。
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(nowMillis::get); // 创建使用可控时钟的熔断状态机。

        breaker.record("weather", "工具执行超时：weather"); // 登记天气工具第一次最终超时。
        breaker.record("weather", "工具执行失败：network"); // 登记天气工具第二次最终失败。
        breaker.record("weather", "工具执行超时：weather"); // 登记天气工具第三次最终失败并触发熔断。

        assertThat(breaker.allow("weather")).isFalse(); // 断言天气工具在打开窗口内被拒绝。
        assertThat(breaker.allow("calculator")).isTrue(); // 断言其他工具不受天气工具失败影响。
        nowMillis.set(30_000L); // 将单调时间推进到熔断窗口到期点。
        assertThat(breaker.allow("weather")).isTrue(); // 断言到期后允许唯一半开探测。
        assertThat(breaker.allow("weather")).isFalse(); // 断言半开探测进行时拒绝并发第二次调用。
        breaker.record("weather", "晴朗"); // 登记半开探测成功结果。

        assertThat(breaker.allow("weather")).isTrue(); // 断言成功探测关闭熔断并恢复正常调用。
    } // 结束熔断与半开恢复测试。
} // 结束工具熔断状态机测试类。
