package com.jaycong.dodo.agent; // 将适配器放在 Agent 包中，便于它直接实现核心层定义的模型端口。

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 将 Spring AI 的 ChatModel 适配为 Agent 需要的最小文本流端口。
 * 这个适配器把框架特有 API 隔离在边界处，使 Agent 核心只依赖 ChatStreamPort。
 */
@Component
public class SpringAiChatStreamAdapter implements ChatStreamPort { // 实现核心端口，把 Spring AI 转换为纯文本 Flux。

    private final ChatClient chatClient; // 保存线程安全的对话客户端，供每一轮消息创建独立请求。

    public SpringAiChatStreamAdapter(ChatModel chatModel) { // 通过构造注入接收 Spring AI 自动配置好的具体模型。
        this.chatClient = ChatClient.builder(chatModel).build(); // 基于模型构建客户端，并集中封装后续的提示词调用入口。
    } // 结束适配器构造方法。

    /**
     * 把一条用户消息交给模型，并返回按照片段异步产生的文本流。
     * 此处不订阅 Flux，订阅和任务生命周期由上层 StreamingChatAgent 统一管理。
     *
     * @param message 本轮需要发送给大模型的用户消息
     * @return 延迟执行的模型文本片段流
     */
    @Override
    public Flux<String> stream(String message) { // 接收业务层消息，并返回尚未被订阅的响应流。
        return chatClient.prompt() // 创建一次新的提示请求规格，不复用上一轮对话的可变请求状态。
                .user(message) // 把方法参数设置为本次请求的用户消息。
                .stream() // 选择 Spring AI 的流式调用模式，让模型响应按片段异步到达。
                .content(); // 只提取响应中的文本内容，隐藏框架响应对象和元数据。
    } // 结束模型流适配方法。
} // 结束 Spring AI 模型适配器定义。
