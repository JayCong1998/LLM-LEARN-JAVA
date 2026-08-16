package com.jaycong.dodo.agent; // 将 Spring AI 决策适配器测试放在对应 Agent 包中。

import com.jaycong.dodo.tool.AgentToolRegistry;
import com.jaycong.dodo.tool.WeatherTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiReactModelAdapterTest { // 定义同步模型调用与工具选项映射测试。

    @Test
    void enablesManualToolsAndCanDisableThemForForcedFinalAnswer() { // 验证正常决策和强制收尾使用不同的工具能力配置。
        CapturingChatModel model = new CapturingChatModel(); // 创建记录真实 Prompt 的本地假模型。
        AgentToolRegistry registry = new AgentToolRegistry( // 创建只包含天气工具的真实注册表。
                Arrays.asList(ToolCallbacks.from(new WeatherTool()))); // 使用 Spring AI 官方反射转换获得真实工具定义。
        SpringAiReactModelAdapter adapter = new SpringAiReactModelAdapter(model, registry); // 组装待测适配器及其边界依赖。
        List<Message> messages = List.of(new UserMessage("北京天气如何？")); // 创建模型决策所需的有序消息上下文。

        AssistantMessage enabledResult = adapter.decide(messages, true); // 发起一次允许模型选择工具的正常决策。
        AssistantMessage disabledResult = adapter.decide(messages, false); // 发起一次禁止继续调用工具的收尾决策。

        assertThat(enabledResult.getText()).isEqualTo("模型回答"); // 断言适配器返回 ChatResponse 中的 AssistantMessage。
        assertThat(disabledResult.getText()).isEqualTo("模型回答"); // 断言关闭工具不会改变响应对象提取规则。
        assertThat(model.prompts).hasSize(2); // 断言两次决策都各自创建并调用了一个 Prompt。
        assertThat(model.prompts.getFirst().getInstructions()).containsExactlyElementsOf(messages); // 断言完整消息列表按原顺序传给模型。
        ToolCallingChatOptions enabledOptions = (ToolCallingChatOptions) model.prompts.getFirst().getOptions(); // 读取正常决策的工具调用选项。
        assertThat(enabledOptions.getToolCallbacks()).hasSize(1); // 断言启用时向模型声明注册表中的天气工具。
        assertThat(enabledOptions.getInternalToolExecutionEnabled()).isFalse(); // 断言 Spring AI 自动执行明确关闭，由手写循环接管。
        ToolCallingChatOptions disabledOptions = (ToolCallingChatOptions) model.prompts.getLast().getOptions(); // 读取强制收尾决策的工具调用选项。
        assertThat(disabledOptions.getToolCallbacks()).isEmpty(); // 断言关闭时模型无法再次选择任何工具。
        assertThat(disabledOptions.getInternalToolExecutionEnabled()).isFalse(); // 断言收尾调用也保持框架自动执行关闭。
    } // 结束工具开关映射测试。

    private static final class CapturingChatModel implements ChatModel { // 定义不访问网络且保留 Prompt 的同步假模型。
        private final List<Prompt> prompts = new ArrayList<>(); // 保存每次 call 收到的 Prompt，供测试检查选项。

        @Override
        public ChatResponse call(Prompt prompt) { // 实现适配器实际使用的同步模型调用。
            prompts.add(prompt); // 在返回结果前记录完整 Prompt 快照。
            return new ChatResponse(List.of(new Generation(new AssistantMessage("模型回答")))); // 返回含单个助手消息的真实 ChatResponse。
        } // 结束同步模型调用实现。

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) { // 实现 ChatModel 要求的流式方法，但本测试不使用它。
            throw new UnsupportedOperationException("本测试只验证同步决策"); // 若适配器误用流式调用则立即暴露失败。
        } // 结束未使用的流式方法实现。
    } // 结束捕获 Prompt 的测试模型类型。
} // 结束 Spring AI ReAct 适配器测试类。
