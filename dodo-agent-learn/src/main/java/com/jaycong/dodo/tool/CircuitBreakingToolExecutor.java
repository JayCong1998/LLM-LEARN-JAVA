package com.jaycong.dodo.tool; // 将熔断保护包装器放在工具执行链所在包中。

/**
 * 在进入下游重试、超时与真实工具前按工具名检查熔断状态。
 * 仅对已实际执行完成的最终 Observation 记账，因此被拒绝、取消和参数校验错误不会污染熔断计数。
 */
public class CircuitBreakingToolExecutor implements ToolExecutionPort { // 定义可组合到工具执行链最外层的熔断包装器。

    private final ToolExecutionPort delegate; // 保存被保护的下游执行端口。
    private final ToolCircuitBreaker circuitBreaker; // 保存按工具名隔离的熔断状态机。

    public CircuitBreakingToolExecutor(ToolExecutionPort delegate, ToolCircuitBreaker circuitBreaker) { // 接收下游端口与状态机以支持生产装配和确定性测试。
        this.delegate = delegate; // 保存下游端口，只有得到许可时才会调用。
        this.circuitBreaker = circuitBreaker; // 保存熔断状态机，统一判断和记录工具状态。
    } // 结束熔断包装器构造方法。

    @Override
    public String execute(String toolName, String arguments) { // 兼容尚未携带完整运行上下文的既有调用方。
        return execute(new ToolExecutionContext("", toolName, ""), arguments); // 用空的非空标识构造兼容上下文并复用统一保护路径。
    } // 结束兼容工具执行方法。

    @Override
    public String execute(String toolName, String arguments, ToolRetryListener retryListener) { // 兼容既有重试通知调用方。
        return execute(new ToolExecutionContext("", toolName, ""), arguments, retryListener); // 用兼容上下文进入统一保护路径，保留重试事件通知。
    } // 结束兼容重试通知执行方法。

    @Override
    public String execute(ToolExecutionContext context, String arguments) { // 执行携带会话和调用标识的工具调用。
        return execute(context, arguments, (attempt, delayMillis) -> { }); // 为无重试事件消费者提供空通知器，仍让下游保持统一调用形态。
    } // 结束上下文工具执行方法。

    @Override
    public String execute(ToolExecutionContext context, String arguments, ToolRetryListener retryListener) { // 在熔断许可与最终结果记录之间保护下游执行链。
        if (!circuitBreaker.allow(context.toolName())) { // 熔断打开或半开探测已被占用时不得触达下游资源。
            return "工具已熔断：" + context.toolName(); // 返回可安全展示和回填的确定性拒绝 Observation。
        } // 结束熔断拒绝分支。
        String observation = delegate.execute(context, arguments, retryListener); // 仅在许可后执行完整下游链，确保重试完成后得到最终 Observation。
        circuitBreaker.record(context.toolName(), observation); // 仅记录最终结果，避免每次重试错误地累计熔断失败次数。
        return observation; // 将原始最终 Observation 返回给 Agent 回填上下文。
    } // 结束受熔断保护的上下文执行方法。
} // 结束熔断工具执行包装器定义。
