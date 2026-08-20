package com.jaycong.dodo.agent; // 将最终回答流式输出 Agent 放在核心 Agent 包中。

import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.memory.ConversationTurn;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保留 stream 路由入口，但使用 ReAct 决策直接返回最终答案，不再二次请求模型伪造分片。
 */
public class WebSearchReactStreamAgent { // 定义只负责选择流式最终回答模式的薄适配层。

    //聊天客户端
    private ChatClient chatClient;
    //模型
    private ChatModel chatModel;
    //任务管理器
    private InMemoryTaskRegistry tasks;
    //持久会话记忆
    private ConversationMemory memory;


    /**
     * 初始化创建agent
     *
     * @param chatModel
     * @param tasks
     * @param memory
     */
    public WebSearchReactStreamAgent(ChatModel chatModel,
                                     InMemoryTaskRegistry tasks,
                                     ConversationMemory memory) {
        this.chatModel = chatModel;
        this.tasks = tasks;
        this.memory = memory;
        //创建chatClient
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * WebSearchReactAgent 系统提示词
     */
    public static String getWebSearchPrompt() {
        return """
                ## 角色
                你是一个智能体问答助手，名字叫做：豆豆，英文名叫dodo，帮助用户解决问题，在调用工具前，必须思考清楚，禁止提前给出一些推断性/不确定性的信息给用户。
                
                ## 当前系统时间：
                %s
                
                ## 核心思考原则
                1. 用户问题的核心要素：包含【主体】+【时间维度】+【核心事件】；
                2. 验证信息必要性：需要调用搜索工具来验证；
                3. 注意筛选与用户问题中时效性一致的答案，过滤掉无关的或者过期的信息。
                
                ## 最终答案规则
                输出最终自然语言答案，禁止包含工具调用格式
                
                ## 输出规范
                1. 尽可能的使用 emoji 表情，让回答更友好
                2. 使用结构化方式呈现信息（列表、表格、分类等）
                3. 对关键内容进行强调加粗说明
                4. 保持回答的清晰度和易读性
                5. 尽可能全面详细的回答用户问题
                
                ## 强制要求
                1. 工具调用必须只通过 ToolCall 字段输出
                2. 本轮无工具调用时，必须输出最终答案
                3. 禁止输出干扰解析的结构
                4. 已有全部信息时，不要再调用工具
                """.formatted(java.time.LocalDateTime.now());
    }

    public Flux<AgentStreamEvent> stream(String conversationId, String message) { // 按 SSE 协议启动一次最终回答流式 ReAct 运行。
        boolean hasRunningTask = tasks.hasRunningTask(conversationId);
        if (hasRunningTask) {
            return Flux.error(new IllegalStateException("会话正在进行中"));
        }

        tasks.register(conversationId, () -> {
        });

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();


        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && memory != null;

        messages.add(new SystemMessage(WebSearchReactStreamAgent.getWebSearchPrompt()));

        List<ConversationTurn> conversationTurns = memory.get(conversationId);
        for (ConversationTurn turn : conversationTurns) { // 按窗口保存的时间顺序回放每轮完整问答。
            messages.add(new UserMessage(turn.userContent())); // 把历史问题恢复成模型可识别的用户角色消息。
            messages.add(new AssistantMessage(turn.assistantContent())); // 把对应最终回答恢复成助手角色消息并保持问答配对。
        }
        messages.add(new UserMessage(message));

        scheduleRound(messages, sink, conversationId);
        return sink.asFlux().doOnNext(e -> {
            System.out.println("数据：" + e.content());
        }).doOnComplete(() -> {
            System.out.println("完成");
        }).doOnError(e -> {
            System.out.println("错误：" + e.getMessage());
        }).doFinally(signalType -> {
            System.out.println("信号：" + signalType);
        });
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink, String conversationId) {
        Disposable subscribe = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(e -> System.out.println(e))
                //第一次对话完成后进入finishRound
                .doOnComplete(() -> finishRound(messages, sink, conversationId))
                .doOnError(e -> System.out.println("失败：" + e))
                .subscribe();

        // 保存Disposable到任务管理器
        if (conversationId != null && tasks != null) {
            tasks.attach(conversationId, subscribe);
        }
    }

    private void finishRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink, String conversationId) {


    }

}
