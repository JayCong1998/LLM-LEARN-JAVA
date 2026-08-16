package com.jaycong.dodo.tool; // 将工具对象到统一注册表的 Spring 装配放在工具边界包中。

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * 把本地 @Tool 方法转换成 Spring AI ToolCallback，并建立手写 ReAct 使用的唯一注册表。
 * 模型声明和人工执行都读取该注册表，从配置层保证两边看到的是同一组工具。
 */
@Configuration(proxyBeanMethods = false)
public class AgentToolConfiguration { // 定义阶段二本地工具的显式 Spring 装配。

    @Bean
    public AgentToolRegistry agentToolRegistry( // 创建供模型适配器和 ManualReactAgent 共享的工具注册表 Bean。
            WeatherTool weatherTool, // 接收 Spring 容器管理的确定性天气工具。
            CalculatorTool calculatorTool) { // 接收 Spring 容器管理的精确计算器工具。
        return new AgentToolRegistry( // 使用转换后的 ToolCallback 列表创建不可变注册表。
                Arrays.asList(ToolCallbacks.from(weatherTool, calculatorTool))); // 通过官方反射工具读取 @Tool 元数据和调用方法。
    } // 结束工具注册表 Bean 创建方法。
} // 结束 Agent 工具装配配置。
