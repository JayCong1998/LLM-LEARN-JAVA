package com.jaycong.dodo.react; // 将最终回答流式输出 Agent 放在核心 Agent 包中。

import com.jaycong.dodo.agent.AgentStreamEvent;
import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.CalculatorTool;
import com.jaycong.dodo.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * 保留 stream 路由入口，但使用 ReAct 决策直接返回最终答案，不再二次请求模型伪造分片。
 */
public class JayCongReactAgent { // 定义只负责选择流式最终回答模式的薄适配层。

    //聊天客户端
    private ChatClient chatClient;
    //模型
    private ChatModel chatModel;
    //任务管理器
    private InMemoryTaskRegistry tasks;
    //持久会话记忆
    private ConversationMemory memory;
    private final List<ToolCallback> tools;

    private static Integer maxRounds = 2;

    /**
     * 初始化创建agent
     *
     * @param chatModel
     */
    public JayCongReactAgent(ChatModel chatModel,List<ToolCallback> otherTools) {
        this.chatModel = chatModel;

        tools = new ArrayList<>();

        //初始化工具
        tools.addAll(Arrays.asList(ToolCallbacks.from(new CalculatorTool(),new WeatherTool())));
        tools.addAll(otherTools);
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(tools)
                .build();
        this.chatClient = ChatClient.builder(chatModel)
                .defaultOptions(toolOptions)
                .build();
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
        JayCongReactRunContext context = new JayCongReactRunContext(conversationId);

        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        context.addMessage(new SystemMessage(JayCongReactAgent.getWebSearchPrompt()));
        context.addMessage(new UserMessage(message));
        context.setCurrentUserMessage(message);

        scheduleRound(sink, context);
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

    private void scheduleRound(Sinks.Many<AgentStreamEvent> sink, JayCongReactRunContext context) {
        //轮次+1
        context.getRoundCounter().incrementAndGet();
        //初始化轮次状态、一开始是未知
        RoundState state = new RoundState();

        chatClient.prompt()
                .messages(context.getMessages())
                .stream()
                .chatResponse()
                //这里publishOn让后续流程在boundedElastic线程池中执行
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                //第一次对话完成后进入finishRound
                .doOnComplete(() -> finishRound(sink, context, state))
                .doOnError(e -> System.out.println("失败：" + e))
                .subscribe();
    }

    /**
     * 处理每个轮次中的模型多次生成的文本块
     *
     * @param chunk
     * @param sink
     * @param state
     */
    private void processChunk(ChatResponse chunk, Sinks.Many<AgentStreamEvent> sink, RoundState state) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        Generation gen = chunk.getResult();
        //文本
        String text = gen.getOutput().getText();
        //工具调用
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        // 一旦发现 tool_call，立即进入 TOOL_CALL 模式
        if (tc != null && !tc.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            for (AssistantMessage.ToolCall incoming : tc) {
                mergeToolCall(state, incoming);
            }
            return;
        }

        // 还没出现 tool_call，发送并缓存文本
        if (text != null) {
            sink.tryEmitNext(AgentStreamEvent.text(text));
            state.textBuffer.append(text);
        }
    }

    //合并工具调用
    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {

        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);

            if (existing.id().equals(incoming.id())) {

                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");

                state.toolCalls.set(i,
                        new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs)
                );
                return;
            }
        }

        // 新 tool call
        state.toolCalls.add(incoming);
    }

    private void finishRound(Sinks.Many<AgentStreamEvent> sink, JayCongReactRunContext context, RoundState state) {
        // 如果整轮都没有 tool_call，才是最终答案
        if (state.mode != RoundMode.TOOL_CALL) {
            sink.tryEmitComplete();
            //结束标识
            context.getFinished().set(true);
            return;
        }

        //超过最大轮次
        if (maxRounds > 0 && context.getRoundCounter().get() >= maxRounds) {
            forceFinalStream(sink, context);
            return;
        }

        // TOOL_CALL
        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build();
        context.addMessage(assistantMsg);

        executeToolCalls(sink, state.toolCalls, context, state, () -> {
            if (!context.getFinished().get()) {
                scheduleRound(sink, context);
            }
        });
    }

    private void forceFinalStream(Sinks.Many<AgentStreamEvent> sink, JayCongReactRunContext context) {
        // 创建新的消息列表，确保系统提示词在最前面
        List<Message> newMessages = new ArrayList<>();

        // 添加系统提示词
        newMessages.add(new SystemMessage(JayCongReactAgent.getWebSearchPrompt()));

        // 添加原有消息（跳过系统消息）
        for (Message msg : context.getMessages()) {
            if (!(msg instanceof SystemMessage)) {
                newMessages.add(msg);
            }
        }

        // 添加限制提示
        newMessages.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，
                直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));

        // 替换原消息列表
        context.getMessages().clear();
        context.getMessages().addAll(newMessages);

        chatClient.prompt()
                .messages(context.getMessages())
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }

                    String text = chunk.getResult()
                            .getOutput()
                            .getText();

                    sink.tryEmitNext(AgentStreamEvent.text(text));
                })
                .doOnComplete(() -> {
                    context.getFinished().set(true);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    context.getFinished().set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();
    }

    private void executeToolCalls(Sinks.Many<AgentStreamEvent> sink, List<AssistantMessage.ToolCall> toolCalls, JayCongReactRunContext context, RoundState state, Runnable onComplete) {
        List<ToolResponseMessage.ToolResponse> sortedResponses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            String toolName = tc.name();
            String argsJson = tc.arguments();
            sink.tryEmitNext(AgentStreamEvent.toolStart(tc.name(), tc.id(), tc.arguments())); // 在真实执行前向客户端暴露 Action。

            //找到工具
            ToolCallback callback = findTool(toolName);

            //调用工具
            Object result = callback.call(argsJson);
            String resultStr = result.toString();

            //返回结果
            sortedResponses.add(new ToolResponseMessage.ToolResponse(
                    tc.id(), toolName, resultStr));

            //工具调用结束事件
            sink.tryEmitNext(AgentStreamEvent.toolEnd(tc.name(), tc.id(), resultStr)); // 在未取消时输出可关联的工具结束事件。
        }

        //所有工具调用完成，添加一次响应结果
        context.addMessage(ToolResponseMessage.builder()
                .responses(sortedResponses)
                .build());

        //下次循环
        onComplete.run();
    }

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 运行模式：未知、最终答案、工具调用
     */
    private enum RoundMode {
        UNKNOWN,
        FINAL_ANSWER,
        TOOL_CALL
    }

    /**
     * 每轮执行的状态标记位
     */
    private static class RoundState {
        RoundMode mode = RoundMode.UNKNOWN;
        StringBuilder textBuffer = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    }
}
