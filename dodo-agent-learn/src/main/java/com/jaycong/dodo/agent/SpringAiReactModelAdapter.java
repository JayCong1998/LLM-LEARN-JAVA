package com.jaycong.dodo.agent; // 将 Spring AI 实现放在核心端口旁边，明确它是外部模型适配器。

import com.jaycong.dodo.tool.AgentToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 使用 Spring AI ChatClient 实现一次同步 ReAct 模型决策。
 * 无论是否向模型声明工具，都关闭框架内部自动执行，确保工具生命周期完全由 ManualReactAgent 控制。
 */
@Component
public class SpringAiReactModelAdapter implements ReactModelPort { // 实现核心模型端口并隔离 Spring AI 请求细节。

    private final ChatClient chatClient; // 保存线程安全的 ChatClient，用于每一轮创建独立请求规格。
    private final AgentToolRegistry toolRegistry; // 保存当前 Agent 允许向模型声明的工具集合。

    public SpringAiReactModelAdapter( // 通过构造器显式声明生产模型和工具目录依赖。
            ChatModel chatModel, // 接收 Spring AI 自动配置的具体聊天模型。
            AgentToolRegistry toolRegistry) { // 接收统一工具注册表并结束构造参数列表。
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build(); // 基于模型创建同步和流式调用均可复用的客户端。
        this.toolRegistry = toolRegistry; // 保存工具目录，后续根据每轮开关决定是否暴露回调。
    } // 结束 Spring AI 适配器构造方法。

    /**
     * 将当前完整消息上下文交给模型生成一个 AssistantMessage。
     * 该调用是阻塞操作，调用方必须把整个 ReAct 循环调度到 boundedElastic，不能占用 WebFlux 事件线程。
     */
    @Override
    public AssistantMessage decide( // 执行一次模型决策并返回包含文本或工具调用的助手消息。
            List<Message> messages, // 接收从系统提示到最新 Observation 的完整有序上下文。
            boolean toolsEnabled) { // 接收本轮是否允许继续产生工具调用的状态开关。
        ToolCallingChatOptions options = ToolCallingChatOptions.builder() // 创建支持显式工具控制的请求选项构建器。
                .toolCallbacks(toolsEnabled ? toolRegistry.callbacks() : new org.springframework.ai.tool.ToolCallback[0]) // 正常轮声明全部工具，收尾轮传入空工具集。
                .internalToolExecutionEnabled(false) // 禁止 Spring AI 自动执行，保留手写 ReAct 的控制权与可观察事件。
                .build(); // 构建本次决策独享的不可变工具调用选项。
        ChatResponse response = chatClient.prompt() // 创建一个不复用可变请求状态的新提示规格。
                .messages(messages) // 按原始顺序设置系统、用户、助手和工具响应消息。
                .options(options) // 应用本轮的工具声明以及禁止自动执行设置。
                .call() // 使用同步调用取得完整模型决策，外层负责隔离阻塞线程。
                .chatResponse(); // 保留结构化 ChatResponse，以便取得 AssistantMessage 中的工具调用。
        return response.getResult().getOutput(); // 返回首个 Generation 的助手消息给手写循环判断下一步。
    } // 结束单轮模型决策方法。
} // 结束 Spring AI ReAct 模型适配器定义。
