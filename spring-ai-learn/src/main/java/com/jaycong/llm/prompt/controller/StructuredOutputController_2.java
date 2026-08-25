package com.jaycong.llm.prompt.controller;


import com.jaycong.llm.prompt.entity.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @author pyc
 * @since 2026-08-15 11:09
 */
@Slf4j
@RestController
@RequestMapping("/structure")
public class StructuredOutputController_2 implements InitializingBean {

    @Autowired
    private ChatModel openAiChatModel;

    private ChatClient chatClient;

    @RequestMapping("/convertBean1")
    public String demo1() {
        PromptTemplate promptTemplate = PromptTemplate.builder().template("请给我推荐几本NBA有关的书，输出格式：{format}").build();

        BeanOutputConverter<Book> converter = new BeanOutputConverter<Book>(Book.class);

        String resp = chatClient.prompt(promptTemplate.create(Map.of("format", converter.getFormat())))
                .call().chatResponse().getResult().getOutput().getText();

        Book book = converter.convert(resp);

        System.out.println(book.toString());

        return book.name() + " " + book.author() + " " + book.desc() + " " + book.price() + " " + book.publisher();
    }

    @RequestMapping("/convertBean2")
    public String demo2() {
        Book book = chatClient.prompt("请给我推荐几本心理学有关的书")
                .call().entity(Book.class);
        System.out.println(book.toString());
        return book.name() + " 、 " + book.author() + " 、 " + book.desc() + " 、 " + book.price() + " 、 " + book.publisher();
    }


    @RequestMapping("/convertList")
    public String convertList() {
        List<Book> book = chatClient.prompt("请给我推荐几本心理学有关的书")
                .call().entity(new ParameterizedTypeReference<List<Book>>() {
                });

        System.out.println(book.toString());
        return book.toString();
    }

    @RequestMapping("/convertMap")
    public String convertMap() {
        Map<String, Object> book = chatClient.prompt("请给我推荐几本心理学有关的书，书的内容包括书名、作者、价格、上市时间等信息，以书名作为key，书的信息作为value")
                .call().entity(new MapOutputConverter());

        System.out.println(book.toString());
        return book.toString();
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        chatClient = ChatClient.builder(openAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

}
