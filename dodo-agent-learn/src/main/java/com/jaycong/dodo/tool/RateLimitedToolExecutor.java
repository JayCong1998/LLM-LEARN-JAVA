package com.jaycong.dodo.tool; // 将限流包装器放在工具执行链所在包中。

/**
 * 在进入下游重试与真实工具前按会话和工具申请一次调用许可。
 * 许可只在首次真实工具调用时消耗，限流拒绝作为 Observation 返回且不影响熔断失败计数。
 */
public class RateLimitedToolExecutor implements ToolExecutionPort { // 定义可组合到工具执行链中的限流包装器。

    private final ToolExecutionPort delegate; // 保存只有取得许可时才允许访问的下游端口。
    private final ToolRateLimiter rateLimiter; // 保存会话与工具维度的滑动窗口限流状态机。

    public RateLimitedToolExecutor(ToolExecutionPort delegate, ToolRateLimiter rateLimiter) { // 接收下游端口和限流器以支持生产装配与单元测试。
        this.delegate = delegate; // 保存下游端口，避免限流器感知重试或超时实现。
        this.rateLimiter = rateLimiter; // 保存限流器，统一管理许可消耗。
    } // 结束限流包装器构造方法。

    @Override
    public String execute(String toolName, String arguments) { // 兼容尚未携带完整运行上下文的既有调用方。
        return execute(new ToolExecutionContext("", toolName, ""), arguments); // 使用空的非空标识构造兼容上下文并复用统一限流路径。
    } // 结束兼容工具执行方法。

    @Override
    public String execute(String toolName, String arguments, ToolRetryListener retryListener) { // 兼容既有重试通知调用方。
        return execute(new ToolExecutionContext("", toolName, ""), arguments, retryListener); // 使用兼容上下文进入统一限流路径并保留重试通知。
    } // 结束兼容重试通知执行方法。

    @Override
    public String execute(ToolExecutionContext context, String arguments) { // 执行携带会话和调用标识的工具调用。
        return execute(context, arguments, (attempt, delayMillis) -> { }); // 为无重试事件消费者提供空通知器并复用保护逻辑。
    } // 结束上下文工具执行方法。

    @Override
    public String execute(ToolExecutionContext context, String arguments, ToolRetryListener retryListener) { // 在申请会话工具许可后执行下游链。
        if (!rateLimiter.tryAcquire(context.conversationId(), context.toolName())) { // 当前会话对该工具在最近一分钟已使用十次真实调用。
            return "工具调用已限流：" + context.toolName(); // 返回稳定、可回填且不暴露内部状态的拒绝 Observation。
        } // 结束限流拒绝分支。
        return delegate.execute(context, arguments, retryListener); // 许可成功后把上下文和重试通知完整交给下游执行链。
    } // 结束受限流保护的上下文执行方法。
} // 结束限流工具执行包装器定义。
