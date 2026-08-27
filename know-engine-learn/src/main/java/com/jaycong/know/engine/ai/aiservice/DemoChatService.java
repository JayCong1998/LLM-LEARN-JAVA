package com.jaycong.know.engine.ai.aiservice;


import dev.langchain4j.service.spring.AiService;
import reactor.core.publisher.Flux;

/**
 * 基于大语言模型的流式对话服务。
 */
@AiService
public interface DemoChatService {

    /**
     * 根据用户消息生成流式回答。
     *
     * @param message 用户输入的消息，不能为空
     * @return 按生成顺序输出的回答片段流
     */
    Flux<String> stream(String message);
}
