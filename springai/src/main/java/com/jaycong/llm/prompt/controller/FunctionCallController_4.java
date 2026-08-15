package com.jaycong.llm.prompt.controller;


import com.jaycong.llm.function.TimeTools;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author pyc
 * @since 2026-08-15 12:38
 */
@Slf4j
@RestController
@RequestMapping("/function")
public class FunctionCallController_4 {

    @Autowired
    private OpenAiChatModel chatModel;

    private ChatClient chatClient;

    @Autowired
    private ChatMemory chatMemory;

    @GetMapping("/demo1")
    public String demo1(@RequestParam("query") String query) {
        log.info("chat request => {}", query);
        return chatClient.prompt().toolNames("getTimeFunction").tools(new TimeTools()).user(query).call().content();
    }


    @PostConstruct
    public void init() {
        chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(),new SimpleLoggerAdvisor())
                .build();
    }
}
