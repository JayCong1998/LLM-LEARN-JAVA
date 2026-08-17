package com.jaycong.dodo.tool; // 将工具执行端口兼容性测试放在工具包中。

import org.junit.jupiter.api.Test; // 引入 JUnit 测试注解。

import static org.assertj.core.api.Assertions.assertThat; // 引入 AssertJ 结果断言。

class ToolExecutionPortTest { // 定义会话上下文执行重载的兼容性测试。

    @Test
    void delegatesContextAwareExecutionToExistingLambdaPort() { // 验证新上下文重载不破坏已有两参数 Lambda 实现。
        ToolExecutionContext context = new ToolExecutionContext("conversation-1", "weather", "call-1"); // 创建保护链需要读取的安全会话与工具标识。
        ToolExecutionPort port = (toolName, arguments) -> "正常结果"; // 使用现有两参数 Lambda 创建最小端口实现。

        String observation = port.execute(context, "{}"); // 通过新上下文重载执行旧 Lambda 端口。

        assertThat(observation).isEqualTo("正常结果"); // 断言默认实现正确委托到原有执行方法。
    } // 结束上下文重载兼容性测试。
} // 结束工具执行端口测试类。
