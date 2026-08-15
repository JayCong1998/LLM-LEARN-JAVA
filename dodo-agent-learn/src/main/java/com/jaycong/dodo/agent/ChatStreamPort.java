package com.jaycong.dodo.agent;

import reactor.core.publisher.Flux;

/**
 * Agent 依赖的模型输出端口。
 * 业务代码只理解文本片段，不依赖具体模型厂商或 SDK。
 */
@FunctionalInterface
public interface ChatStreamPort {

    Flux<String> stream(String message);
}
