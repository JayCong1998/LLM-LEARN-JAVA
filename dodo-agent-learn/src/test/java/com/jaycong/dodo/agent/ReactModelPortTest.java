package com.jaycong.dodo.agent; // 将模型端口契约测试放在 Agent 核心包中。

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactModelPortTest { // 定义手写 ReAct 与具体模型实现之间的最小契约测试。

    @Test
    void fakePortCanCaptureDecisionInputWithoutApiKey() { // 证明核心 Agent 测试只需假端口而无需真实模型连接。
        List<Message> capturedMessages = new ArrayList<>(); // 创建列表保存假端口收到的消息快照。
        List<Boolean> capturedToolFlags = new ArrayList<>(); // 创建列表保存是否允许模型选择工具的标记。
        ReactModelPort port = (messages, toolsEnabled) -> { // 使用 Lambda 实现计划固定的单方法端口。
            capturedMessages.addAll(messages); // 复制当前决策消息，供调用后验证上下文传递。
            capturedToolFlags.add(toolsEnabled); // 记录当前决策是否向模型暴露工具。
            return new AssistantMessage("最终回答"); // 返回不含工具调用的稳定测试回答。
        }; // 结束无需 API key 的假模型定义。
        Message userMessage = new UserMessage("你好"); // 创建一条真实 Spring AI 用户消息作为端口输入。

        AssistantMessage result = port.decide(List.of(userMessage), true); // 请求一次允许工具选择的模型决策。

        assertThat(result.getText()).isEqualTo("最终回答"); // 断言端口返回模型助手消息而不是传输层字符串。
        assertThat(capturedMessages).containsExactly(userMessage); // 断言完整有序消息上下文被交给模型边界。
        assertThat(capturedToolFlags).containsExactly(true); // 断言工具开关能够独立传入模型适配器。
    } // 结束模型端口可替换性测试。
} // 结束 ReAct 模型端口测试类。
