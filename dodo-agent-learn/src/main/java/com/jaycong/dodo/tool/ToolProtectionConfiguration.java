package com.jaycong.dodo.tool; // 将工具保护链的生产装配配置放在工具包中。

import org.springframework.context.annotation.Bean; // 引入声明显式生产 Bean 的配置方法标记。
import org.springframework.context.annotation.Configuration; // 引入声明工具保护链配置类的 Spring 标记。
import org.springframework.context.annotation.Primary; // 引入指定 Agent 注入最外层保护端口的优先标记。

/**
 * 显式固定工具执行包装顺序，避免多个 ToolExecutionPort Bean 被 Spring 按类型随机选择。
 * 熔断必须位于最外层以拒绝已知故障工具，限流随后只消耗真实调用额度，重试仅包裹一次限时执行。
 */
@Configuration(proxyBeanMethods = false) // 使用无代理配置，配置方法之间不依赖容器回调。
public class ToolProtectionConfiguration { // 定义生产环境唯一的完整工具保护链装配点。

    @Bean
    ToolCircuitBreaker toolCircuitBreaker() { // 创建进程内按工具名隔离的熔断状态机 Bean。
        return new ToolCircuitBreaker(); // 使用生产单调时钟构造共享熔断状态。
    } // 结束熔断状态机 Bean 创建方法。

    @Bean
    ToolRateLimiter toolRateLimiter() { // 创建进程内按会话和工具隔离的限流状态机 Bean。
        return new ToolRateLimiter(); // 使用生产单调时钟构造共享限流窗口。
    } // 结束限流状态机 Bean 创建方法。

    @Bean
    @Primary
    ToolExecutionPort protectedToolExecutor( // 声明供 Agent 注入的最外层工具执行端口。
            TimedToolExecutor timedToolExecutor, // 接收已管理虚拟线程与单次超时的最内层执行器。
            ToolCircuitBreaker circuitBreaker, // 接收共享的按工具熔断状态。
            ToolRateLimiter rateLimiter) { // 接收共享的按会话和工具限流状态并结束参数列表。
        ToolExecutionPort retryingExecutor = new RetryingToolExecutor(timedToolExecutor); // 先在单次限时执行器外组合最多两次超时重试。
        ToolExecutionPort rateLimitedExecutor = new RateLimitedToolExecutor(retryingExecutor, rateLimiter); // 再在重试链外仅为首次真实调用消耗限流额度。
        return new CircuitBreakingToolExecutor(rateLimitedExecutor, circuitBreaker); // 最外层熔断在下游执行前快速拒绝已知故障工具。
    } // 结束完整工具保护链 Bean 创建方法。
} // 结束工具保护链生产配置定义。
