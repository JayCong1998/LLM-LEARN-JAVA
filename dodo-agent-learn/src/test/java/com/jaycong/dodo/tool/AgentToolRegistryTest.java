package com.jaycong.dodo.tool; // 将工具注册表测试放在对应工具包中。

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRegistryTest { // 定义工具发现、执行和失败隔离的行为测试。

    @Test
    void exposesCallbacksAndExecutesToolByName() { // 验证同一份注册信息同时服务模型声明和人工执行。
        ToolCallback weather = callback("weather", arguments -> "收到参数：" + arguments); // 创建会回显 JSON 参数的真实测试回调。
        AgentToolRegistry registry = new AgentToolRegistry(List.of(weather)); // 使用单个回调构造待测注册表。

        assertThat(registry.callbacks()).containsExactly(weather); // 断言模型端能取得原始回调定义且顺序不变。
        assertThat(registry.execute("weather", "{\"city\":\"北京\"}")) // 通过工具名执行回调并传递原始参数。
                .isEqualTo("收到参数：{\"city\":\"北京\"}"); // 断言注册表没有改写工具的正常 Observation。
    } // 结束正常注册和执行测试。

    @Test
    void convertsToolFailuresIntoStableObservations() { // 验证工具层错误不会以异常形式击穿 ReAct 主循环。
        ToolCallback broken = callback("broken", arguments -> { // 创建一个模拟本地执行失败的测试回调。
            throw new IllegalStateException("boom"); // 抛出明确异常以验证注册表的错误边界。
        }); // 结束失败回调构造。
        ToolCallback empty = callback("empty", arguments -> null); // 创建返回 null 的异常工具实现。
        AgentToolRegistry registry = new AgentToolRegistry(List.of(broken, empty)); // 同时注册两类异常回调。

        assertThat(registry.execute("missing", "{}")) // 尝试执行注册表中不存在的工具。
                .isEqualTo("工具执行失败：未找到工具 missing"); // 断言未知工具返回稳定 Observation。
        assertThat(registry.execute("broken", "{}")) // 执行会抛出异常的工具。
                .isEqualTo("工具执行失败：boom"); // 断言异常消息被转换而不继续向外抛出。
        assertThat(registry.execute("empty", "{}")) // 执行错误返回 null 的工具。
                .isEqualTo("工具执行失败：工具未返回结果"); // 断言空返回值被转换为明确 Observation。
    } // 结束工具失败隔离测试。

    private ToolCallback callback(String name, ToolExecutor executor) { // 创建只用于测试的最小 ToolCallback 实现。
        return new ToolCallback() { // 返回实现 Spring AI 工具协议的匿名对象。
            @Override
            public ToolDefinition getToolDefinition() { // 提供注册表建立名称索引所需的工具定义。
                return ToolDefinition.builder() // 使用 Spring AI 官方构建器创建不可变定义。
                        .name(name) // 设置测试指定的唯一工具名称。
                        .description("测试工具") // 提供必需的简短工具说明。
                        .inputSchema("{\"type\":\"object\"}") // 提供合法的最小 JSON Schema。
                        .build(); // 完成工具定义构建。
            } // 结束工具定义方法。

            @Override
            public String call(String arguments) { // 接收注册表原样传递的 JSON 参数。
                return executor.execute(arguments); // 委托给当前测试场景提供的执行逻辑。
            } // 结束测试工具执行方法。
        }; // 结束匿名 ToolCallback 实现。
    } // 结束测试回调工厂方法。

    @FunctionalInterface
    private interface ToolExecutor { // 定义允许测试通过 Lambda 定制工具结果的最小接口。
        String execute(String arguments); // 根据原始 JSON 参数返回结果或抛出测试异常。
    } // 结束测试执行器接口。
} // 结束工具注册表测试类。
