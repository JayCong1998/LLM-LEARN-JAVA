package com.jaycong.dodo.tool; // 将限时工具执行器测试放在工具包中，以便构造短超时测试实例。

import org.junit.jupiter.api.Test; // 引入 JUnit 测试标记。
import org.springframework.ai.tool.ToolCallback; // 引入与生产工具相同的 Spring AI 回调契约。
import org.springframework.ai.tool.definition.ToolDefinition; // 引入创建最小测试工具定义所需的类型。

import java.time.Duration; // 引入表达测试超时窗口的时间类型。
import java.util.List; // 引入组装单个工具注册表的不可变列表工厂。
import java.util.concurrent.CountDownLatch; // 引入协调异步工具进入和中断时机的同步器。
import java.util.concurrent.ExecutorService; // 引入模拟外层 Agent 等待线程的执行器接口。
import java.util.concurrent.Executors; // 引入创建测试等待线程的工厂。
import java.util.concurrent.Future; // 引入取消外层等待任务的句柄类型。
import java.util.concurrent.TimeUnit; // 引入显式等待单位，避免测试永久阻塞。

import static org.assertj.core.api.Assertions.assertThat; // 引入 AssertJ 流式断言。

class TimedToolExecutorTest { // 定义工具超时和中断传播的行为测试。

    @Test // 标记验证限时内成功工具调用的测试方法。
    void returnsObservationWhenToolFinishesBeforeDeadline() { // 验证执行器不会改变正常工具 Observation。
        try (TimedToolExecutor executor = executor(arguments -> "正常结果", Duration.ofMillis(100))) { // 创建带短但充足等待窗口的真实限时执行器。
            String observation = executor.execute("fast", "{}"); // 通过限时边界执行立即返回的工具。
            assertThat(observation).isEqualTo("正常结果"); // 断言正常结果不会被包装成错误文本。
        } // 关闭执行器，释放该测试创建的虚拟线程资源。
    } // 结束正常完成测试。

    @Test // 标记验证工具超时后中断请求的测试方法。
    void returnsTimeoutObservationAndInterruptsSlowTool() throws Exception { // 验证超时不会阻塞 Agent，且会向合作工具发送中断。
        CountDownLatch interrupted = new CountDownLatch(1); // 创建等待工具确认收到中断的信号。
        try (TimedToolExecutor executor = executor(arguments -> { // 创建会一直等待直到被中断的真实工具。
            try { // 进入可中断的阻塞区域以模拟慢速 I/O。
                new CountDownLatch(1).await(); // 永久等待，迫使限时执行器走超时路径。
                return "不应返回"; // 为编译器提供理论不可达的返回值。
            } catch (InterruptedException error) { // 接收执行器在超时时发送的协作式中断。
                interrupted.countDown(); // 通知测试线程工具已经观察到中断。
                Thread.currentThread().interrupt(); // 恢复中断标记以遵守 Java 并发约定。
                return "迟到结果"; // 返回会被超时路径忽略的结果，验证调用方不再等待它。
            } // 结束慢工具的中断处理分支。
        }, Duration.ofMillis(50))) { // 使用足够短的窗口稳定触发超时。
            String observation = executor.execute("slow", "{}"); // 执行受控慢工具并等待限时结果。
            assertThat(observation).isEqualTo("工具执行超时：slow"); // 断言超时被转换为稳定、可回填的 Observation。
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue(); // 断言工具任务最终收到中断请求。
        } // 关闭执行器，避免测试遗留线程资源。
    } // 结束超时与中断测试。

    @Test // 标记验证外层取消会传递到内部工具任务的测试方法。
    void interruptsToolWhenCallingThreadIsCancelled() throws Exception { // 验证停止 Agent 时不再等待或消费工具的迟到结果。
        CountDownLatch entered = new CountDownLatch(1); // 创建等待工具已开始执行的信号。
        CountDownLatch interrupted = new CountDownLatch(1); // 创建等待工具收到取消中断的信号。
        try (TimedToolExecutor executor = executor(arguments -> { // 创建可观察取消行为的阻塞工具。
            entered.countDown(); // 标记内部虚拟线程已经开始工具调用。
            try { // 进入可由 Future.cancel(true) 中断的等待区域。
                new CountDownLatch(1).await(); // 保持工具运行，直到外层任务取消它。
                return "不应返回"; // 为编译器提供理论不可达的返回值。
            } catch (InterruptedException error) { // 接收从外层等待线程传播而来的中断。
                interrupted.countDown(); // 通知测试线程内部工具已收到取消请求。
                Thread.currentThread().interrupt(); // 恢复中断标记以符合协作取消约定。
                return "迟到结果"; // 返回会被取消路径丢弃的结果。
            } // 结束内部工具中断处理分支。
        }, Duration.ofSeconds(1)); // 使用长于测试协调时间的窗口，避免本测试误走超时路径。
             ExecutorService waitingExecutor = Executors.newSingleThreadExecutor()) { // 创建模拟 Agent boundedElastic 等待工具结果的外层线程。
            Future<String> waiting = waitingExecutor.submit(() -> executor.execute("blocking", "{}")); // 在可取消的外层线程中开始等待限时工具。
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); // 确认内部工具已开始后再取消外层等待。
            assertThat(waiting.cancel(true)).isTrue(); // 中断等待线程，模拟任务注册表 dispose 外层工作订阅。
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue(); // 断言执行器把外层中断传播给内部工具 Future。
        } // 关闭两层执行器，保证测试进程没有遗留资源。
    } // 结束取消传播测试。

    private TimedToolExecutor executor(ToolExecutor executor, Duration timeout) { // 统一用一个测试工具和可控超时组装真实执行器。
        return new TimedToolExecutor(new AgentToolRegistry(List.of( // 注册本测试会用到的三个名称，避免名称查找掩盖限时行为。
                callback("fast", executor), // 注册正常完成场景使用的工具名称。
                callback("slow", executor), // 注册超时场景使用的工具名称。
                callback("blocking", executor))), timeout); // 注册取消场景使用的工具名称并创建待测执行器。
    } // 结束测试执行器工厂方法。

    private ToolCallback callback(String name, ToolExecutor executor) { // 根据指定名称和行为创建一个 Spring AI 测试工具回调。
        return new ToolCallback() { // 创建符合 Spring AI 工具协议的匿名测试回调。
            @Override // 实现模型和注册表查询工具元数据的方法。
            public ToolDefinition getToolDefinition() { // 返回名称唯一的最小工具声明。
                return ToolDefinition.builder().name(name).description("测试工具").inputSchema("{\"type\":\"object\"}").build(); // 构造注册表可识别的最小 Schema。
            } // 结束测试工具定义方法。

            @Override // 实现接收模型原始参数并返回 Observation 的工具入口。
            public String call(String arguments) { // 接收本测试不需要解析的 JSON 参数。
                return executor.execute(arguments); // 委托到各测试场景提供的实际行为。
            } // 结束测试工具调用方法。
        }; // 结束匿名测试工具定义。
    } // 结束测试工具回调工厂方法。

    @FunctionalInterface // 标记测试 Lambda 可实现的最小工具行为契约。
    private interface ToolExecutor { // 定义测试工具执行所需的单一方法。
        String execute(String arguments); // 根据原始参数返回 Observation 或进入受控阻塞。
    } // 结束测试工具执行契约。
} // 结束限时工具执行器测试类。
