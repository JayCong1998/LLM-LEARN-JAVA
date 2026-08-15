package com.jaycong.llm.prompt.controller;


import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * @author pyc
 * @since 2026-08-15 11:30
 */
@RestController
@RequestMapping("/memory")
public class ChatMemoryController_3 implements InitializingBean {

    @Autowired
    private ChatModel openAiChatModel;

    private ChatClient chatClient;

    /**
     * jdbcChatMemoryConfiguration
     * 配置开启的时候走
     * 配置没开启的时候走内存
     */
    @Autowired
    private ChatMemory chatMemory;

    @GetMapping("/callConversation")
    public Flux<String> callConversation(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream().content();
    }

    @GetMapping("/getConversation")
    public List<Message> callConversation(String chatId) {
        List<Message> messages = chatMemory.get(chatId);
        return messages;
    }


    @Override
    public void afterPropertiesSet() throws Exception {
//        ChatMemoryAutoConfiguration
//        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(3).build();
        this.chatClient = ChatClient.builder(openAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                .build();
    }

}
