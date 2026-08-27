package com.jaycong.know.engine.ai.controller;

import com.jaycong.know.engine.ai.aiservice.DemoChatService;
import com.jaycong.know.engine.common.api.ApiResponse;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 大语言模型对话接口，提供同步问答与流式问答能力。
 */
@RestController
@RequestMapping("/api/chat")
@Validated
public class ChatController {

    /**
     * 同步聊天模型。
     */
    private final ChatModel chatModel;

    /**
     * 流式聊天模型。
     */
    private final StreamingChatModel streamingChatModel;

    /**
     * LangChain4j 声明式对话服务。
     */
    private final DemoChatService demoChatService;

    /**
     * 创建对话控制器。
     *
     * @param chatModel 同步聊天模型
     * @param streamingChatModel 流式聊天模型
     * @param demoChatService 声明式对话服务
     */
    public ChatController(ChatModel chatModel, StreamingChatModel streamingChatModel, DemoChatService demoChatService) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.demoChatService = demoChatService;
    }

    /**
     * 调用聊天模型并以统一 JSON 响应返回完整答复。
     *
     * @param message 用户输入的消息，不能为空
     * @return 包含完整模型回答的统一响应
     */
    @GetMapping
    public ApiResponse<String> chat(@RequestParam @NotBlank(message = "消息不能为空") String message) {
        return ApiResponse.success(chatModel.chat(message));
    }

    /**
     * 调用聊天模型并按服务端事件流持续返回生成内容。
     *
     * @param message 用户输入的消息，不能为空
     * @return 按生成顺序输出的回答片段流
     */
    @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@RequestParam @NotBlank(message = "消息不能为空") String message) {
        return demoChatService.stream(message);
    }
}
