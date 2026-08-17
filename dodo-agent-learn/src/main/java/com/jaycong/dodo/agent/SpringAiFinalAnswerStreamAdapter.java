package com.jaycong.dodo.agent; // 将 Spring AI 最终回答流适配器放在 Agent 包中。

import java.util.List; // 引入完整有序消息快照类型。
import org.springframework.ai.chat.client.ChatClient; // 引入 Spring AI 高层聊天客户端。
import org.springframework.ai.chat.messages.Message; // 引入要透传给模型的角色消息抽象。
import org.springframework.ai.chat.model.ChatModel; // 引入由 Spring 自动配置的底层模型。
import org.springframework.stereotype.Component; // 引入生产自动装配标记。
import reactor.core.publisher.Flux; // 引入模型输出的异步文本流类型。

/**
 * 将 Spring AI 的最终回答流转换为核心端口的纯文本片段。
 * 此阶段不声明工具，因此模型只能基于共享 ReAct 协调器已回填的 Observation 生成最终文本。
 */
@Component // 注册为流式 Agent 使用的生产最终回答模型边界。
public class SpringAiFinalAnswerStreamAdapter implements FinalAnswerStreamPort { // 实现核心端口并隐藏 ChatClient 细节。

    private final ChatClient chatClient; // 保存线程安全客户端以为每次运行创建独立提示请求。

    public SpringAiFinalAnswerStreamAdapter(ChatModel chatModel) { // 接收 Spring 自动配置的具体聊天模型。
        this.chatClient = ChatClient.builder(chatModel).build(); // 基于模型构建支持流式提示的客户端。
    } // 结束最终回答流适配器构造方法。

    @Override
    public Flux<String> stream(List<Message> messages) { // 传入完整预算消息并返回模型逐片段响应。
        return chatClient.prompt() // 创建本次最终回答独享的提示规格。
                .messages(messages) // 保持系统、历史、当前问题与工具 Observation 的原始顺序。
                .stream() // 选择非阻塞的模型流式调用模式。
                .content() // 只提取文本内容，避免向 Agent 泄漏框架响应对象。
                .filter(fragment -> fragment != null && !fragment.isEmpty()); // 丢弃框架可能产生的空片段，避免客户端收到无意义 text 事件。
    } // 结束最终回答流请求方法。
} // 结束 Spring AI 最终回答流适配器定义。
