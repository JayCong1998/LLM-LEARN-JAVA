package com.jaycong.dodo.tool; // 将重试执行器测试放在工具包中以访问后续包内测试构造器。

import org.junit.jupiter.api.Test; // 引入 JUnit 测试方法标记。

import java.util.ArrayList; // 引入收集通知的可变列表实现。
import java.util.List; // 引入按顺序断言通知内容的列表类型。

import static org.assertj.core.api.Assertions.assertThat; // 引入 AssertJ 流式断言。

class RetryingToolExecutorTest { // 定义工具超时重试与端口兼容性的行为测试。

    @Test
    void defaultRetryOverloadKeepsLambdaPortCompatible() { // 验证默认通知重载不会破坏既有两参数 Lambda 端口。
        ToolExecutionPort port = (toolName, arguments) -> "正常结果"; // 使用现有的两参数 Lambda 创建最小工具执行端口。
        List<String> notifications = new ArrayList<>(); // 收集理论上不应发生的重试通知。

        String observation = port.execute("weather", "{}", (attempt, delayMillis) -> notifications.add(attempt + ":" + delayMillis)); // 通过新增重载调用旧 Lambda 端口。

        assertThat(observation).isEqualTo("正常结果"); // 断言默认重载仍委托原有单次执行方法。
        assertThat(notifications).isEmpty(); // 断言普通端口不会凭空产生重试通知。
    } // 结束端口兼容性测试。
} // 结束重试执行器测试类。
