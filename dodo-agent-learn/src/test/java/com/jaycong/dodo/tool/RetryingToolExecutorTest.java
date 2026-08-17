package com.jaycong.dodo.tool; // 将重试执行器测试放在工具包中以访问后续包内测试构造器。

import org.junit.jupiter.api.Test; // 引入 JUnit 测试方法标记。

import java.util.ArrayList; // 引入收集通知的可变列表实现。
import java.util.ArrayDeque; // 引入按尝试顺序提供脚本结果的双端队列。
import java.util.List; // 引入按顺序断言通知内容的列表类型。
import java.util.Queue; // 引入脚本化 Observation 队列的抽象类型。
import java.util.concurrent.atomic.AtomicInteger; // 引入准确统计实际执行次数的原子计数器。

import static org.assertj.core.api.Assertions.assertThat; // 引入 AssertJ 流式断言。
import static org.assertj.core.api.Assertions.assertThatThrownBy; // 引入 AssertJ 异常断言。

class RetryingToolExecutorTest { // 定义工具超时重试与端口兼容性的行为测试。

    @Test
    void defaultRetryOverloadKeepsLambdaPortCompatible() { // 验证默认通知重载不会破坏既有两参数 Lambda 端口。
        ToolExecutionPort port = (toolName, arguments) -> "正常结果"; // 使用现有的两参数 Lambda 创建最小工具执行端口。
        List<String> notifications = new ArrayList<>(); // 收集理论上不应发生的重试通知。

        String observation = port.execute("weather", "{}", (attempt, delayMillis) -> notifications.add(attempt + ":" + delayMillis)); // 通过新增重载调用旧 Lambda 端口。

        assertThat(observation).isEqualTo("正常结果"); // 断言默认重载仍委托原有单次执行方法。
        assertThat(notifications).isEmpty(); // 断言普通端口不会凭空产生重试通知。
    } // 结束端口兼容性测试。

    @Test
    void retriesTimedOutToolTwiceWithIncreasingBackoffUntilThirdAttemptSucceeds() { // 验证前两次超时后按固定退避执行第三次并返回成功 Observation。
        AtomicInteger delegateCalls = new AtomicInteger(); // 统计底层单次限时执行器实际被调用的次数。
        Queue<String> observations = new ArrayDeque<>(List.of("工具执行超时：weather", "工具执行超时：weather", "最终成功")); // 按尝试顺序配置两次超时和第三次成功。
        List<String> notifications = new ArrayList<>(); // 收集重试通知以检查尝试次数和延迟。
        List<Long> delays = new ArrayList<>(); // 收集测试等待器收到的退避时间。
        ToolExecutionPort delegate = (toolName, arguments) -> { // 创建模拟单次限时执行结果的底层端口。
            delegateCalls.incrementAndGet(); // 每次真实尝试都增加计数。
            return observations.remove(); // 返回当前脚本 Observation 并推进到下一次尝试。
        }; // 结束脚本化底层端口定义。
        RetryingToolExecutor executor = new RetryingToolExecutor(delegate, delays::add); // 创建不实际休眠的可控重试执行器。

        String observation = executor.execute("weather", "{}", (attempt, delayMillis) -> notifications.add(attempt + ":" + delayMillis)); // 执行一次具备重试通知的工具调用。

        assertThat(observation).isEqualTo("最终成功"); // 断言第三次正常结果成为最终 Observation。
        assertThat(delegateCalls).hasValue(3); // 断言首次加两次重试总共执行三次。
        assertThat(notifications).containsExactly("2:200", "3:400"); // 断言通知使用即将开始的尝试编号和固定退避。
        assertThat(delays).containsExactly(200L, 400L); // 断言等待器接收严格递增的退避时长。
    } // 结束两次退避后成功测试。

    @Test
    void doesNotRetryObservationThatIsNotExactTimeoutText() { // 验证未知工具和业务失败不会被文本包含关系误判为可重试超时。
        AtomicInteger delegateCalls = new AtomicInteger(); // 统计非超时场景是否被错误重复执行。
        ToolExecutionPort delegate = (toolName, arguments) -> { // 创建返回普通失败 Observation 的底层端口。
            delegateCalls.incrementAndGet(); // 记录唯一允许的执行次数。
            return "工具执行失败：bad request"; // 返回不符合严格超时格式的业务失败文本。
        }; // 结束普通失败底层端口定义。
        RetryingToolExecutor executor = new RetryingToolExecutor(delegate, delayMillis -> { }); // 创建无需记录等待的重试执行器。

        String observation = executor.execute("weather", "{}", (attempt, delayMillis) -> { }); // 执行带空通知回调的普通失败调用。

        assertThat(observation).isEqualTo("工具执行失败：bad request"); // 断言普通失败文本保持原样返回。
        assertThat(delegateCalls).hasValue(1); // 断言非超时 Observation 不进入重试循环。
    } // 结束非超时不重试测试。

    @Test
    void restoresInterruptAndDoesNotStartNextAttemptWhenBackoffIsInterrupted() { // 验证取消打断退避后不会开始第二次真实工具执行。
        AtomicInteger delegateCalls = new AtomicInteger(); // 统计取消后是否错误开始新的工具尝试。
        ToolExecutionPort delegate = (toolName, arguments) -> { // 创建首次必然返回超时的底层端口。
            delegateCalls.incrementAndGet(); // 记录已经发生的首次工具尝试。
            return "工具执行超时：weather"; // 强制执行器进入第二次尝试前的退避路径。
        }; // 结束超时底层端口定义。
        RetryingToolExecutor executor = new RetryingToolExecutor(delegate, delayMillis -> { throw new InterruptedException("cancelled"); }); // 创建模拟外层取消打断等待的测试执行器。

        assertThatThrownBy(() -> executor.execute("weather", "{}", (attempt, delayMillis) -> { })) // 在当前测试线程调用并等待中断传播。
                .hasMessage("工具重试已取消"); // 断言中断被转换为稳定的取消异常。
        assertThat(delegateCalls).hasValue(1); // 断言等待被打断后没有开始第二次实际工具执行。
        assertThat(Thread.currentThread().isInterrupted()).isTrue(); // 断言执行器恢复协作取消所需的中断标记。
        Thread.interrupted(); // 清除测试线程中断标记，避免影响同一工作线程后续测试。
    } // 结束退避中断传播测试。
} // 结束重试执行器测试类。
