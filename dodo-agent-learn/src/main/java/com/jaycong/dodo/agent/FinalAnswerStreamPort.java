package com.jaycong.dodo.agent; // 将最终回答流端口放在 Agent 核心包中。

import java.util.List; // 引入完整有序模型消息快照类型。
import org.springframework.ai.chat.messages.Message; // 引入 Spring AI 角色消息抽象。
import reactor.core.publisher.Flux; // 引入表示异步文本片段序列的 Reactor 类型。

/**
 * 隔离最终回答阶段所需的模型 Token 流。
 * 调用方负责取消、聚合和成功持久化，端口只根据完整且已预算的消息快照产生文本片段。
 */
@FunctionalInterface // 保持端口可由测试 Lambda 或生产适配器直接实现。
public interface FinalAnswerStreamPort { // 定义最终回答片段流的最小外部依赖。

    Flux<String> stream(List<Message> messages); // 根据完整消息快照返回尚未订阅的最终回答文本片段流。
} // 结束最终回答流端口定义。
