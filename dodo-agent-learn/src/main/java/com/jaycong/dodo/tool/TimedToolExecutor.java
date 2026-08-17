package com.jaycong.dodo.tool; // 将限时工具执行策略放在工具包中，避免污染 ReAct 编排逻辑。

import jakarta.annotation.PreDestroy; // 引入 Spring Bean 销毁时释放执行器资源的生命周期标记。
import org.springframework.stereotype.Component; // 引入让 Spring 自动装配生产执行器的组件标记。
import org.springframework.beans.factory.annotation.Autowired; // 引入显式指定生产构造器的依赖注入标记。

import java.time.Duration; // 引入表达固定工具等待上限的时间类型。
import java.util.concurrent.ExecutionException; // 引入包装内部工具任务异常的 Future 异常类型。
import java.util.concurrent.ExecutorService; // 引入管理虚拟线程任务生命周期的执行器接口。
import java.util.concurrent.Executors; // 引入创建每任务虚拟线程执行器的工厂。
import java.util.concurrent.Future; // 引入控制单次工具任务等待和取消的句柄。
import java.util.concurrent.TimeUnit; // 引入 Future 超时等待所需的时间单位。
import java.util.concurrent.TimeoutException; // 引入识别超过固定等待上限的异常类型。

/**
 * 为同步工具调用提供固定等待上限，并把超时转换为模型可继续处理的 Observation。
 * 外层 ReAct 线程可以被停止请求中断；此时必须同时取消内部虚拟线程任务，避免 Agent 继续等待或消费迟到结果。
 * Future.cancel(true) 仅发送协作式中断请求，第三方工具仍需自行正确响应中断或配置其底层 I/O 超时。
 */
@Component
public class TimedToolExecutor implements ToolExecutionPort, AutoCloseable { // 定义生产使用的三秒限时工具执行器。

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3); // 固定教学阶段所有真实工具共用的最大等待时间。
    private final AgentToolRegistry registry; // 保存只负责名称查找和业务 Observation 的既有注册表。
    private final Duration timeout; // 保存当前实例等待每次工具任务结果的不可变时限。
    private final ExecutorService executor; // 保存为每次工具调用创建独立虚拟线程的受管执行器。

    @Autowired
    public TimedToolExecutor(AgentToolRegistry registry) { // 接收 Spring 注入的注册表并创建生产默认超时实例。
        this(registry, DEFAULT_TIMEOUT); // 复用可测试构造器，确保生产和测试共享同一资源策略。
    } // 结束生产构造方法。

    TimedToolExecutor(AgentToolRegistry registry, Duration timeout) { // 接收包内测试可控的短时限并创建真实执行器。
        if (timeout == null || timeout.isNegative() || timeout.isZero()) { // 拒绝无法表达有效等待窗口的配置。
            throw new IllegalArgumentException("工具超时时间必须大于零"); // 在任务提交前暴露错误配置，避免产生不可预测行为。
        } // 结束超时配置校验分支。
        this.registry = registry; // 保存注册表，供虚拟线程执行真实工具。
        this.timeout = timeout; // 保存已校验时限，供每次 Future 等待复用。
        this.executor = Executors.newVirtualThreadPerTaskExecutor(); // 创建由本 Bean 关闭的轻量级每任务虚拟线程执行器。
    } // 结束可测试构造方法。

    @Override
    public String execute(String toolName, String arguments) { // 在独立虚拟线程中执行工具并受限等待结果。
        Future<String> future = executor.submit(() -> registry.execute(toolName, arguments)); // 提交真实注册表调用，使外层 ReAct 工作者不直接占用工具线程。
        try { // 等待正常结果、超时或外层取消三种互斥结果。
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); // 最多等待固定时限并直接返回正常 Observation。
        } catch (TimeoutException error) { // 工具在限制内没有完成时进入可恢复超时路径。
            future.cancel(true); // 向内部虚拟线程请求中断，避免 Agent 已超时仍继续等待工具。
            return "工具执行超时：" + toolName; // 返回模型可理解的稳定 Observation，而不是终止整次 ReAct。
        } catch (InterruptedException error) { // 外层 Agent 等待线程被停止请求或浏览器断开中断时进入取消传播路径。
            future.cancel(true); // 把外层中断继续传播给当前真正执行工具的虚拟线程。
            Thread.currentThread().interrupt(); // 恢复中断标记，使上层循环能够保持正确的取消语义。
            throw new ToolExecutionInterruptedException(error); // 抛出专用运行时异常，让 Agent 不把取消误写成 tool_end Observation。
        } catch (ExecutionException error) { // 防御注册表之外的意外工作线程失败，避免 Future 包装异常泄漏到 SSE。
            return "工具执行失败：" + errorMessage(error); // 转换为与现有注册表一致的可恢复失败 Observation。
        } // 结束限时等待结果分支。
    } // 结束限时工具执行方法。

    @PreDestroy
    @Override
    public void close() { // 在 Spring 停止或测试结束时释放该实例拥有的虚拟线程执行器。
        executor.close(); // 停止接收新任务并等待合作任务结束，避免资源跨应用生命周期泄漏。
    } // 结束执行器关闭方法。

    private String errorMessage(ExecutionException error) { // 从 Future 包装异常提取始终可展示的失败说明。
        Throwable cause = error.getCause(); // 优先读取内部工具真正抛出的异常原因。
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) { // 防御没有原因或没有可读消息的异常。
            return cause == null ? error.getClass().getSimpleName() : cause.getClass().getSimpleName(); // 回退到异常类型，保证 Observation 不为空。
        } // 结束不可读异常消息保护分支。
        return cause.getMessage(); // 返回具体失败说明，供模型决定下一步操作。
    } // 结束 Future 异常文本转换方法。

    private static final class ToolExecutionInterruptedException extends RuntimeException { // 定义仅供执行器向上保留取消语义的私有异常类型。

        private ToolExecutionInterruptedException(InterruptedException cause) { // 接收触发取消传播的原始中断异常。
            super("工具执行已取消", cause); // 使用稳定消息包装原因，供意外未取消场景诊断。
        } // 结束取消异常构造方法。
    } // 结束私有取消异常类型。
} // 结束限时工具执行器定义。
