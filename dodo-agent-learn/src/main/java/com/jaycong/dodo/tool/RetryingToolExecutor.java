package com.jaycong.dodo.tool; // 将超时重试策略放在工具包中，避免把可靠性细节混入 ReAct 编排器。

import org.springframework.beans.factory.annotation.Autowired; // 引入显式指定生产构造器的依赖注入标记。

/**
 * 只对单次限时执行返回的稳定超时 Observation 做有限重试。
 * 单次三秒上限仍由 TimedToolExecutor 负责；本类只组合多次尝试、退避与通知，便于独立测试和后续替换策略。
 * 退避等待在 Agent 的 boundedElastic 工作者发生，外层取消中断该线程时必须停止下一次尝试并保留中断语义。
 */
public class RetryingToolExecutor implements ToolExecutionPort { // 定义只处理超时重试的生产工具执行端口。

    private static final int MAX_ATTEMPTS = 3; // 定义首次执行加两次重试后的总尝试次数上限。
    private static final long SECOND_ATTEMPT_DELAY_MILLIS = 200L; // 定义第二次尝试前的固定退避时长。
    private static final long THIRD_ATTEMPT_DELAY_MILLIS = 400L; // 定义第三次尝试前的固定退避时长。
    private static final String TIMEOUT_PREFIX = "工具执行超时："; // 定义与 TimedToolExecutor 协作的稳定超时 Observation 前缀。
    private final ToolExecutionPort delegate; // 保存负责单次实际执行的限时端口。
    private final Sleeper sleeper; // 保存可替换的等待边界，以便测试不产生真实延迟。

    @Autowired // 在存在包内测试构造器时明确让 Spring 选择此生产依赖组合。
    public RetryingToolExecutor(TimedToolExecutor delegate) { // 接收具体单次限时 Bean，避免注入自身的 ToolExecutionPort 抽象。
        this(delegate, Thread::sleep); // 生产环境使用可中断的线程休眠实现固定退避。
    } // 结束生产重试执行器构造方法。

    RetryingToolExecutor(ToolExecutionPort delegate, Sleeper sleeper) { // 接收包内测试可控的底层端口和等待实现。
        this.delegate = delegate; // 保存单次执行边界，重试器不关心工具注册表或虚拟线程细节。
        this.sleeper = sleeper; // 保存可中断等待器，供每次超时后的退避调用。
    } // 结束可测试重试执行器构造方法。

    @Override
    public String execute(String toolName, String arguments) { // 支持没有可观察重试通知的既有调用方。
        return execute(toolName, arguments, (attempt, delayMillis) -> { }); // 使用空通知回调复用完整重试策略。
    } // 结束无通知执行方法。

    @Override
    public String execute(String toolName, String arguments, ToolRetryListener retryListener) { // 执行首次调用，并仅在精确超时时安排有限重试。
        return execute(new ToolExecutionContext("", toolName, ""), arguments, retryListener); // 使用兼容上下文复用统一重试路径，保持旧调用方行为不变。
    } // 结束带通知重试执行方法。

    @Override
    public String execute(ToolExecutionContext context, String arguments) { // 执行携带会话与调用标识的工具调用且无需向外通知重试。
        return execute(context, arguments, (attempt, delayMillis) -> { }); // 使用空通知回调复用完整的上下文重试策略。
    } // 结束无通知上下文执行方法。

    @Override
    public String execute(ToolExecutionContext context, String arguments, ToolRetryListener retryListener) { // 在保持完整上下文的前提下执行首次调用和有限重试。
        String observation = delegate.execute(context, arguments); // 先执行首次工具调用，使下游限时端口仍可接收完整上下文。
        for (int attempt = 2; attempt <= MAX_ATTEMPTS && isTimeout(observation, context.toolName()); attempt++) { // 仅在上一轮为精确超时时进入第二或第三次尝试。
            long delayMillis = delayBefore(attempt); // 根据即将开始的尝试次数选择固定递增退避。
            retryListener.onRetry(attempt, delayMillis); // 在等待前通知 Agent 产生可观察但不含敏感数据的 SSE 事件。
            sleep(delayMillis); // 等待期间允许外层任务取消通过线程中断阻止后续真实执行。
            observation = delegate.execute(context, arguments); // 退避正常完成后执行下一次受单独三秒限制且保留上下文的工具调用。
        } // 结束最多两次的超时重试循环。
        return observation; // 返回首次成功、重试成功或第三次最终超时的稳定 Observation。
    } // 结束带通知上下文重试执行方法。

    private boolean isTimeout(String observation, String toolName) { // 判断 Observation 是否严格等于当前工具的单次超时结果。
        return (TIMEOUT_PREFIX + toolName).equals(observation); // 采用精确相等比较，避免业务文本被包含匹配误判。
    } // 结束超时 Observation 判断方法。

    private long delayBefore(int attempt) { // 根据即将开始的重试次数返回该次的固定退避。
        return attempt == 2 ? SECOND_ATTEMPT_DELAY_MILLIS : THIRD_ATTEMPT_DELAY_MILLIS; // 第二次等待两百毫秒，第三次等待四百毫秒。
    } // 结束退避时间选择方法。

    private void sleep(long delayMillis) { // 执行可中断退避并将中断转换为明确的取消传播异常。
        try { // 捕获等待器用于协作取消的受检中断异常。
            sleeper.sleep(delayMillis); // 使用生产或测试注入的可中断等待实现。
        } catch (InterruptedException error) { // 外层停止或浏览器断开中断当前 Agent 工作者时进入此分支。
            Thread.currentThread().interrupt(); // 恢复中断标记，保证上层生命周期代码能继续识别取消。
            throw new ToolRetryInterruptedException(error); // 停止循环，禁止开始下一次实际工具执行。
        } // 结束退避中断处理分支。
    } // 结束可中断退避方法。

    @FunctionalInterface // 标记可由 Lambda 提供的最小可中断等待边界。
    interface Sleeper { // 定义重试器等待退避时间所需的窄依赖。

        void sleep(long delayMillis) throws InterruptedException; // 等待指定毫秒数，允许外层取消通过中断提前终止。
    } // 结束可中断等待契约。

    private static final class ToolRetryInterruptedException extends RuntimeException { // 定义只用于向 Agent 保留退避取消语义的异常类型。

        private ToolRetryInterruptedException(InterruptedException cause) { // 接收触发取消传播的原始中断异常。
            super("工具重试已取消", cause); // 使用稳定错误文本帮助意外未取消场景的诊断。
        } // 结束重试取消异常构造方法。
    } // 结束重试取消异常定义。
} // 结束超时重试工具执行器定义。
