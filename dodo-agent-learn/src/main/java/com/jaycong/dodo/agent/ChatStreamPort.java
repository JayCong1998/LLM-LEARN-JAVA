package com.jaycong.dodo.agent; // 将端口接口放在 Agent 包中，使抽象由业务层而不是模型适配层拥有。

import reactor.core.publisher.Flux;

/**
 * Agent 所依赖的最小模型流端口。
 * Agent 只消费文本片段，不感知 Spring AI、模型厂商或底层网络协议。
 */
@FunctionalInterface
public interface ChatStreamPort { // 定义 Agent 核心与具体大模型实现之间的依赖倒置边界。

    Flux<String> stream(String message); // 接收一条用户消息，并以异步流形式返回模型生成的多个文本片段。
} // 结束模型流端口定义。
