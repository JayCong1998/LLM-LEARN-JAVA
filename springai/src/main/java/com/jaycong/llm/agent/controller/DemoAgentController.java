package com.jaycong.llm.agent.controller;


import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author pyc
 * @since 2026-08-14 16:59
 */
@RestController
@RequestMapping("/demo")
public class DemoAgentController implements InitializingBean {

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ChatModel dashScopeChatModel;

    @Autowired
    ToolCallingManager toolCallingManager;

    private ChatClient chatClient;

    @GetMapping("/call")
    public String call(String conversationId) {
        //定义ChatOptions

        return "";
    }

    @Override
    public void afterPropertiesSet() throws Exception {
//        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(3).build();

        this.chatClient = ChatClient.builder(dashScopeChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7)
                                .build()
                ).build();
    }
}
