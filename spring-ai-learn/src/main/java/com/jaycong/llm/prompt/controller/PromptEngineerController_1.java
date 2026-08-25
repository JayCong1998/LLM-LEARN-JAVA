package com.jaycong.llm.prompt.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import java.util.HashMap;
import java.util.Map;


/**
 * @author pyc
 * @since 2026-08-15 09:31
 */
@Slf4j
@RestController
@RequestMapping("/prompt/engineer")
public class PromptEngineerController_1 implements InitializingBean {

    @Autowired
    private ChatModel openAiChatModel;

    private ChatClient chatClient;

    @GetMapping("/demo1")
    public String demo1(@RequestParam String message) {
        return openAiChatModel.call(message);
    }

    @GetMapping("/demo2")
    public Flux<String> demo2(@RequestParam String message) {
        PromptTemplate promptTemplate = new PromptTemplate("请给我推荐几个关于{topic}的开源项目");
        promptTemplate.add("topic", message);
        return chatClient.prompt(promptTemplate.create()).system("你是一个专业的的github项目收集人员").stream().content();
    }

    @GetMapping("/demo3")
    public Flux<String> demo3(@RequestParam String message) {
        String template = """
                请给我推荐几个关于{topic}的开源项目
                """;
        return chatClient.prompt(new PromptTemplate(template).create(Map.of("topic", message)))
                .system("你是一个专业的的github项目收集人员")
                .stream().content();
    }


    @GetMapping("/demo4")
    public Flux<String> demo4(@RequestParam String message) {
        PromptTemplate promptTemplate = PromptTemplate.builder()
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .template("""
                        告诉我 5 部由 <actor> 参演的电影名称。
                        """)
                .build();

        String prompt = promptTemplate.render(Map.of("actor", message));

        return chatClient.prompt(prompt)
                .system("你是一个专业的的电影专家")
                .stream().content();
    }


    @Value("classpath:templates/open-source-system-prompt.st")
    private Resource systemText;

    @GetMapping("/demo5")
    public Flux<String> demo5(@RequestParam(value = "message") String message) {
        HashMap variables = new HashMap();
        variables.put("language", "Java");
        variables.put("topic", message);
        PromptTemplate promptTemplate = PromptTemplate.builder().resource(systemText).variables(variables).build();

        return chatClient.prompt(promptTemplate.create(Map.of("topic", message))).system("你是一个专业的的github项目收集人员").stream().content();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
//        chatClient = ChatClient.builder(dashScopeChatModel)
//                // 实现 Logger 的 Advisor
//                .defaultAdvisors(
//                        new SimpleLoggerAdvisor()
//                ).defaultSystem("请用英文回答问题")
//                // 设置 ChatClient 中 ChatModel 的 Options 参数
//                .defaultOptions(
//                        DashScopeChatOptions.builder()
//                                .temperature(0.7)
//                                .build()
//                )
//                .build();

        chatClient = ChatClient.builder(openAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(
                        new SimpleLoggerAdvisor()
                )
                .build();
    }
}
