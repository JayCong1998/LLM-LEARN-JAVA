package com.jaycong.llm.agent.agent;

import com.jaycong.llm.agent.config.ChatModelConfig;
import com.jaycong.llm.function.SearchTool;
import com.jaycong.llm.function.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class SimpleReactAgent {

    // Base ReAct system prompt: constrains the model to reason, call tools, observe results, and answer.
    public static final String REACT_AGENT_SYSTEM_PROMPT = """
            ## 角色
            你是一个严格遵循 ReAct 模式的智能 AI 助手，会通过 Reasoning → Act(ToolCall) → Observation 的反复循环来逐步解决任务。

            ## 工具调用规则（极其重要）
            1. 如果需要调用工具：必须使用 OpenAI 官方 ToolCall 结构，并且 **只能通过工具调用字段输出**。
            2. 工具调用时：**禁止在 content 中出现任何形式的工具调用文本**（包括 JSON、<tool_call>、函数名、参数、思考、推理或描述）。
            3. 工具调用消息必须是一次性、原子性输出，不得混杂任何解释或内容。
            4. 工具调用前后不得输出任何多余文字、标签、换行、推理轨迹或说明。
            5. 调用工具时：
               -工具参数必须是有效的JSON
               -参数必须简洁，不超过500个字符
               -切勿包含以前的工具结果、原始内容、HTML或长文本
               -仅包括工具所需的最小控制参数

            ## 工具执行结果
            系统会自动将工具执行结果作为 ToolResponseMessage 注入上下文，你只需读取并决定下一步动作。

            ## 最终答案规则
            1. 如果上下文已经拥有了完成任务的全部信息，则不要再调用任何工具。
            2. 在这种情况下，你必须输出最终自然语言答案，且 **禁止包含任何工具调用格式**。
            3. 最终答案只允许是自然语言，不能包含 JSON、思考过程、reasoning、ToolCall 或伪代码。

            ## 强制要求（必须遵守）
            1. 工具调用消息必须只通过 ToolCall 字段输出，不允许在 content 字段体现工具调用迹象。
            2. 如果本轮没有工具调用，则视为任务完成，你必须输出最终答案。
            3. 不允许重复调用同一个工具（名称 + 参数完全一致），除非工具调用失败。
            4. 禁止输出会干扰工具系统解析的任何结构（如 <reason>、<ToolCall>、函数 JSON、或模型内部思考）。
            5. 如果上下文已经包含了完成任务的全部信息，则不要再调用任何工具。
            """;

    // Agent name: identifies this agent instance for future logs, metrics, or multi-agent management.
    private final String name;
    // Chat model: sends requests to the LLM provider and receives model responses.
    private final ChatModel chatModel;
    // Tool callbacks: tools the model can request by emitting tool calls.
    private final List<ToolCallback> tools;
    // Business system prompt: adds domain-specific role and task constraints on top of the ReAct prompt.
    private final String systemPrompt;
    // Chat client: Spring AI facade that combines model, options, tools, and advisors.
    private ChatClient chatClient;
    // Max rounds: upper bound for the ReAct loop to prevent endless tool-call cycles.
    private int maxRounds;
    // Chat memory: stores and retrieves conversation history by conversationId.
    private ChatMemory chatMemory;

    /**
     * 新增 reflection 相关参数
     */
    // 功能增强拦截器
    // Advisors: Spring AI request/response interceptors for enhancement, reflection, or other extensions.
    private List<Advisor> advisors;
    //最大反思轮数
    // Max reflection rounds: limits how many reflection retries can happen before returning an answer.
    private int maxReflectionRounds;

    public SimpleReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds, ChatMemory chatMemory, List<Advisor> advisors, int maxReflectionRounds) {
        // name stores the logical agent name used to distinguish this instance.
        this.name = name;
        // chatModel is the LLM adapter used by ChatClient for every request.
        this.chatModel = chatModel;
        // tools contains all callbacks that can satisfy model-emitted tool calls.
        this.tools = tools;
        // systemPrompt adds business-specific instructions after the base ReAct prompt.
        this.systemPrompt = systemPrompt;
        // maxRounds limits the number of ReAct turns for one user request.
        this.maxRounds = maxRounds;
        // chatMemory stores and reads history when a conversationId is provided.
        this.chatMemory = chatMemory;

        // 新增 reflection 相关参数
        // maxReflectionRounds limits retries requested by reflection advisors.
        this.maxReflectionRounds = maxReflectionRounds;
        // advisors are registered on ChatClient to intercept or enhance prompt calls.
        this.advisors = advisors;
        // initChatClient assembles model, tools, options, and advisors into one client.
        initChatClient();

        // chatClient must be available before call or stream can run.
        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

    private void initChatClient() {
        try {
            // toolOptions 保存工具调用配置，核心作用是告诉模型有哪些工具，并禁止框架自动执行工具。
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    // tools 是构造器传入的工具回调集合，会被注册到当前 ChatClient。
                    .toolCallbacks(tools)
                    // false 表示工具调用由 SimpleReactAgent 手动执行，方便把 ToolResponseMessage 放回上下文。
                    .internalToolExecutionEnabled(false)
                    // 构建不可变的工具调用配置对象。
                    .build();

            // builder 是 ChatClient 的构造器，负责组合 chatModel、工具、advisor 和默认参数。
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            // advisors 为空时不注册，避免 Spring AI 接收到空 advisor 列表。
            if (!CollectionUtils.isEmpty(advisors)) {
                // defaultAdvisors 表示之后每次 prompt 调用都会经过这些 advisor。
                builder.defaultAdvisors(advisors);
            }
            // chatClient 是最终可调用对象，同时设置默认工具参数和默认工具回调。
            this.chatClient = builder.defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
        } catch (Exception e) {
            // e 保存初始化时的原始异常，包装后继续抛出，便于调用方看到明确的初始化失败信息。
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 非流式输出
     *
     * @param question
     * @return
     */
    public String call(String question) {
        // conversationId 传 null，表示本次调用不启用会话记忆。
        return callInternal(null, question);
    }

    // 带会话记忆
    public String call(String conversationId, String question) {
        // conversationId 标识同一段会话，question 是当前用户输入。
        return callInternal(conversationId, question);
    }

    public String callInternal(String conversationId, String question) {
        // messages 是本轮请求的完整上下文，使用 synchronizedList 是为了后续工具回填时具备基本线程安全。
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        // useMemory 表示是否启用记忆：必须同时有 conversationId 和 chatMemory。
        boolean useMemory = conversationId != null && chatMemory != null;

        // 注入通用 ReAct 系统提示词，约束模型如何调用工具和给出最终答案。
        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        // 注入业务系统提示词，补充当前 Agent 的角色或领域要求。
        messages.add(new SystemMessage(systemPrompt));

        // ===== 加载历史记忆 =====
        if (useMemory) {
            // history 是当前 conversationId 对应的历史消息列表。
            List<Message> history = chatMemory.get(conversationId);
            // 只有历史存在且非空时才加入上下文，避免插入空消息集合。
            if (history != null && !history.isEmpty()) {
                // 将历史对话拼接到系统提示词之后、当前用户问题之前。
                messages.addAll(history);
            }
        }

        // 将当前问题包进 <question> 标签，降低模型把用户问题和系统规则混淆的概率。
        messages.add(new UserMessage("<question>" + question + "</question>"));

        // 添加记忆
        if (useMemory) {
            // 记忆中保存原始用户问题，不保存包裹标签，方便后续对话自然恢复。
            chatMemory.add(conversationId, new UserMessage(question));
        }

        // round 记录 ReAct 主循环执行轮数，用于 maxRounds 限制。
        int round = 0;

        // reflectionRound 记录反思重试次数，用于 maxReflectionRounds 限制。
        int reflectionRound = 0;

        while (true) {
            // 每进入一次循环就表示模型完成一轮推理或工具决策。
            round++;
            // maxRounds 大于 0 时启用轮数上限，超过后强制要求模型总结。
            if (maxRounds > 0 && round > maxRounds) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRounds);
                // 追加一个用户消息，明确告诉模型不能继续调用工具，只能基于现有上下文回答。
                messages.add(new UserMessage("""
                        你已达到最大推理轮次限制。
                        请基于当前已有的上下文信息，
                        直接给出最终答案。
                        禁止再调用任何工具。
                        如果信息不完整，请合理总结和说明。
                        """));

                // finalText 保存强制总结时模型返回的最终文本。
                String finalText = chatClient.prompt().messages(messages).call().content();
                // 启用记忆时，把最终答案写回会话历史。
                if (useMemory) {
                    chatMemory.add(conversationId, new AssistantMessage(finalText));
                }
                // 返回强制总结后的最终答案。
                return finalText;
            }

            // chatResponse 保存本轮 ChatClient 的完整响应，包含文本、工具调用和 advisor 上下文。
            ChatClientResponse chatResponse = chatClient
                    // 开始构建一次 prompt 请求。
                    .prompt()
                    // 使用当前累计的消息上下文。
                    .messages(messages)
                    // 同步调用模型。
                    .call()
                    // 获取包含上下文信息的 ChatClientResponse。
                    .chatClientResponse();

            // aiText 是模型本轮输出的自然语言文本；如果本轮是纯工具调用，可能为空或不是最终答案。
            String aiText = chatResponse.chatResponse().getResult().getOutput().getText();

            // builder 用于创建 AssistantMessage，后续需要把模型的 toolCalls 原样放回上下文。
            AssistantMessage.Builder builder = AssistantMessage.builder().content(aiText);

            // ===== 没有工具调用，视为最终答案 =====
            if (!chatResponse.chatResponse().hasToolCalls()) {
                // 没有工具调用时，先检查 advisor 是否要求反思。
                if (maxReflectionRounds > 0 && Boolean.TRUE.equals(chatResponse.context().get("reflection.required"))) {
                    // reflectionRound 达到上限时不再重试，直接返回当前文本。
                    if (reflectionRound >= maxReflectionRounds) {
                        log.warn("======= Reflection 最大轮次已达，直接输出结论 =======");
                        // 启用记忆时保存当前模型答案。
                        if (useMemory) {
                            chatMemory.add(conversationId, new AssistantMessage(aiText));
                        }
                        // 返回当前模型答案。
                        return aiText;
                    }
                    // 记录一次反思重试。
                    reflectionRound++;
                    log.info("===== 当前反思机制，第 {} 轮次 =====", reflectionRound);

                    // feedback 是 advisor 写入上下文的反思建议，用来指导模型重新规划。
                    String feedback = (String) chatResponse.context().get("reflection.feedback");

                    // 注入反思反馈，引导模型重新规划
                    // 把反思意见作为 AssistantMessage 加入上下文，使下一轮模型能看到改进方向。
                    messages.add(new AssistantMessage("""
                            【Reflection Feedback】
                            %s

                            请你根据以上反思意见重新规划任务，
                            必要时可以重新调用工具，
                            然后再给出最终答案。
                            """.formatted(feedback)));

                    // 进入下一轮循环，让模型基于反思反馈重新回答或重新调用工具。
                    continue;
                }

                // 没有工具调用也不需要反思时，当前文本就是最终答案。
                if (useMemory) {
                    // 保存最终答案到会话记忆。
                    chatMemory.add(conversationId, new AssistantMessage(aiText));
                }

                // 返回最终答案。
                return aiText;
            }

            // ===== 有工具调用：执行工具 =====
            // 把模型产生的工具调用封装成 AssistantMessage 回填到上下文，保持 OpenAI 工具调用协议完整。
            messages.add(builder.toolCalls(chatResponse.chatResponse().getResult().getOutput().getToolCalls()).build());

            // 遍历本轮模型要求执行的所有工具调用。
            chatResponse.chatResponse()
                    .getResult()
                    .getOutput()
                    .getToolCalls()
                    .forEach(toolCall -> {
                        // toolName 是模型请求调用的工具名称。
                        String toolName = toolCall.name();
                        // argsJson 是模型传给工具的 JSON 参数字符串。
                        String argsJson = toolCall.arguments();

                        // callback 是按名称匹配到的本地工具实现。
                        ToolCallback callback = findTool(toolName);
                        // 找不到工具时，构造错误 ToolResponseMessage 返回给模型。
                        if (callback == null) {
                            addErrorToolResponse(messages, toolCall, "工具未找到：" + toolName);
                            return;
                        }

                        // result 保存工具执行后的原始返回对象。
                        Object result;
                        try {
                            // 调用工具并传入模型生成的 JSON 参数。
                            result = callback.call(argsJson);
                            // tr 是符合 Spring AI 协议的单个工具响应，绑定 toolCall.id 供模型关联。
                            ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result.toString());

                            // 将工具响应消息加入上下文，下一轮模型会基于 Observation 继续推理。
                            messages.add(ToolResponseMessage.builder().responses(List.of(tr)).build());
                        } catch (Exception ex) {
                            // 工具异常时，把异常信息作为工具响应返回，让模型决定如何补救或总结。
                            addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage());
                        }
                    });
        }
    }


    /**
     * 运行模式：未知、最终答案、工具调用
     */
    private enum RoundMode {
        // UNKNOWN 表示当前流式轮次尚未判断出是最终回答还是工具调用。
        UNKNOWN,
        // FINAL_ANSWER 表示当前轮次输出的是自然语言最终答案。
        FINAL_ANSWER,
        // TOOL_CALL 表示当前轮次检测到了工具调用，需要执行工具后继续下一轮。
        TOOL_CALL
    }

    /**
     * 每轮执行的状态标记位
     */
    private static class RoundState {
        // mode 记录当前流式轮次的运行模式，finishRound 会根据它决定下一步动作。
        RoundMode mode = RoundMode.UNKNOWN;

        // textBuffer 缓存当前轮已经发送给前端的文本片段。
        StringBuilder textBuffer = new StringBuilder();
        // toolCalls 缓存流式返回中分片到达的工具调用。
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    }


    /**
     * 流式输出
     *
     * @param question
     * @return
     */
    public Flux<String> stream(String question) {
        // conversationId 传 null，表示不启用会话记忆。
        return streamInternal(null, question);
    }

    // 带会话记忆
    public Flux<String> stream(String conversationId, String question) {
        // conversationId 决定读写哪段会话记忆，question 是当前用户输入。
        return streamInternal(conversationId, question);
    }


    public Flux<String> streamInternal(String conversationId, String question) {
        // messages 保存流式调用中的完整上下文消息。
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        // useMemory 表示当前请求是否启用会话记忆。
        boolean useMemory = conversationId != null && chatMemory != null;

        // 加入基础 ReAct 提示词。
        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        // 加入业务提示词。
        messages.add(new SystemMessage(systemPrompt));

        // ===== 加载历史记忆 =====
        if (useMemory) {
            // history 是当前会话 id 下保存过的历史消息。
            List<Message> history = chatMemory.get(conversationId);
            // 历史消息存在时才写入上下文。
            if (history != null && !history.isEmpty()) {
                // 把历史消息追加到当前消息列表。
                messages.addAll(history);
            }
        }

        // 将用户问题加入上下文，标签用于明确问题边界。
        messages.add(new UserMessage("<question>" + question + "</question>"));

        // 添加记忆
        if (useMemory) {
            // 保存原始用户消息到记忆。
            chatMemory.add(conversationId, new UserMessage(question));
        }

        // sink 是单订阅者流式输出通道，用于主动推送模型文本片段。
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 迭代轮次
        // roundCounter 是跨异步回调共享的 ReAct 轮次计数器。
        AtomicLong roundCounter = new AtomicLong(0);
        // 是否发送最终结果标记位
        // hasSentFinalResult 防止重复完成流或重复发送异常。
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);

        // 初始化完成标记为 false。
        hasSentFinalResult.set(false);
        // 初始化轮次计数为 0。
        roundCounter.set(0);

        // 收集最终答案，存储memory
        // finalAnswerBuffer 累积最终流式文本，用于日志和记忆写入。
        StringBuilder finalAnswerBuffer = new StringBuilder();

        // 启动第一轮模型流式调用。
        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId);

        // 返回 Flux 给调用方订阅。
        return sink.asFlux()
                // 收集最终答案
                // 每个输出片段都会追加到最终答案缓存。
                .doOnNext(finalAnswerBuffer::append)
                // 调用方取消订阅时，标记最终结果已结束，避免后台继续发射。
                .doOnCancel(() -> hasSentFinalResult.set(true))
                // 流结束、取消或出错时打印最终缓存。
                .doFinally(signalType -> {
                    log.info("最终答案: {}", finalAnswerBuffer);
                });
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                               StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId) {
        // 轮次+1
        // roundCounter 递增后代表新的一轮模型调用开始。
        roundCounter.incrementAndGet();
        // state 保存当前轮次的文本缓冲、工具调用缓冲和模式。
        RoundState state = new RoundState();

        // 使用当前消息上下文发起流式模型调用。
        chatClient.prompt()
                // messages 是累计上下文，包括系统提示、历史、用户问题、工具响应等。
                .messages(messages)
                // stream 表示使用流式响应。
                .stream()
                // chatResponse 返回 ChatResponse 级别的流，能拿到工具调用分片。
                .chatResponse()
                // 切换到 boundedElastic，避免工具/回调处理阻塞响应线程。
                .publishOn(Schedulers.boundedElastic())
                // 每个响应分片都交给 processChunk 判断是文本还是工具调用。
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                // 当前轮流结束时，根据 state 决定完成输出、执行工具或进入下一轮。
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId))
                // 流式调用出错时，把错误发送给 sink。
                .doOnError(err -> {
                    // 如果还没有结束，才发射错误，避免重复终止流。
                    if (!hasSentFinalResult.get()) {
                        // 标记本次流已经结束。
                        hasSentFinalResult.set(true);
                        // 将异常传递给订阅者。
                        sink.tryEmitError(err);
                    }
                })
                // 订阅后流式请求才真正开始执行。
                .subscribe();
    }

    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {

        // chunk/result/output 任一为空时无法提取文本或工具调用，直接忽略该分片。
        if (chunk == null || chunk.getResult() == null ||
                chunk.getResult().getOutput() == null) return;

        // gen 表示当前响应分片中的单次生成结果。
        Generation gen = chunk.getResult();
        // text 是当前分片中的自然语言文本。
        String text = gen.getOutput().getText();
        // tc 是当前分片中的工具调用列表，流式场景下参数可能分片到达。
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        // 一旦发现 tool_call，立即进入 TOOL_CALL 模式
        if (tc != null && !tc.isEmpty()) {
            // 标记本轮是工具调用轮，轮次结束时不会把文本当最终答案。
            state.mode = RoundMode.TOOL_CALL;

            // 遍历当前分片里的每个工具调用。
            for (AssistantMessage.ToolCall incoming : tc) {
                // incoming 是新到达的工具调用分片，需要合并到当前轮状态中。
                mergeToolCall(state, incoming);
            }
            // 工具调用分片不向外输出文本。
            return;
        }

        // 还没出现 tool_call，发送并缓存文本
        if (text != null) {
            // 将文本片段推送给 Flux 订阅者。
            sink.tryEmitNext(text);
            // 同步缓存文本片段，轮次完成时用于记忆写入。
            state.textBuffer.append(text);
        }
    }

    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {

        // 遍历已缓存的工具调用，查找是否是同一个 toolCall.id 的后续分片。
        for (int i = 0; i < state.toolCalls.size(); i++) {
            // existing 是已经缓存的工具调用分片或已合并结果。
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);

            // 相同 id 表示属于同一次工具调用，需要把 arguments 继续拼接。
            if (existing.id().equals(incoming.id())) {

                // mergedArgs 拼接已有参数片段和新到达参数片段。
                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");

                // 用合并后的 arguments 替换原缓存项。
                state.toolCalls.set(i,
                        new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs)
                );
                // 已完成合并，直接返回。
                return;
            }
        }

        // 新 tool call
        // 如果没有同 id 记录，说明这是一个新的工具调用。
        state.toolCalls.add(incoming);
    }


    /**
     * 轮次结束处理工具调用
     */
    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state, AtomicLong roundCounter,
                             AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId) {

        // 如果整轮都没有 tool_call，才是最终答案
        if (state.mode != RoundMode.TOOL_CALL) {
            // finalText 是当前轮缓存下来的完整文本。
            String finalText = state.textBuffer.toString();
            // 没有工具调用，说明最终答案输出完成，关闭 sink。
            sink.tryEmitComplete();
            // 标记最终结果已经发送，防止后续异步逻辑再次写入。
            hasSentFinalResult.set(true);

            // 启用记忆时保存最终回答。
            if (useMemory) {
                chatMemory.add(conversationId, new AssistantMessage(finalText));
            }
            // 最终答案分支结束。
            return;
        }

        // 如果本轮是工具调用，但已经达到最大轮数，则强制生成最终流式答案。
        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(conversationId, useMemory, messages, sink, hasSentFinalResult);
            return;
        }

        // TOOL_CALL
        // assistantMsg 保存模型刚才发出的工具调用，必须先加入上下文。
        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build();

        // 把工具调用消息加入 messages，保持 OpenAI 工具调用协议顺序。
        messages.add(assistantMsg);

        // 执行所有工具调用，完成后根据回调继续下一轮模型调用。
        executeToolCalls(state.toolCalls, messages, hasSentFinalResult, () -> {
            // 如果执行工具期间没有结束流，则继续下一轮。
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter,
                        hasSentFinalResult, finalAnswerBuffer,
                        useMemory, conversationId);
            }
        });
    }


    private void forceFinalStream(String conversationId, boolean useMemory, List<Message> messages, Sinks.Many<String> sink, AtomicBoolean hasSentFinalResult) {
        // 追加强制最终答案提示，要求模型停止工具调用并直接总结。
        messages.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，
                直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));

        // stringBuilder 缓存强制最终输出的流式文本。
        StringBuilder stringBuilder = new StringBuilder();

        // 再发起一次流式请求，专门用于生成最终答案。
        chatClient.prompt()
                // 使用包含强制总结提示的上下文。
                .messages(messages)
                // 启用流式返回。
                .stream()
                // 读取 ChatResponse 分片。
                .chatResponse()
                // 切换线程池执行后续回调。
                .publishOn(Schedulers.boundedElastic())
                // 逐块处理强制最终答案。
                .doOnNext(chunk -> {
                    // 空分片直接跳过。
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }

                    // text 是当前最终答案分片里的文本。
                    String text = chunk.getResult()
                            .getOutput()
                            .getText();

                    // 只有未结束且文本不为空时才继续推送。
                    if (text != null && !hasSentFinalResult.get()) {
                        // 向订阅者发送文本片段。
                        sink.tryEmitNext(text);
                        // 缓存文本片段，完成后写入记忆。
                        stringBuilder.append(text);
                    }
                })
                // 最终答案流完成后的收尾逻辑。
                .doOnComplete(() -> {
                    // 标记流式输出已经结束。
                    hasSentFinalResult.set(true);
                    // 通知订阅者流完成。
                    sink.tryEmitComplete();
                    // 启用记忆时保存最终答案。
                    if (useMemory) {
                        chatMemory.add(conversationId, new AssistantMessage(stringBuilder.toString()));
                    }
                })
                // 最终答案流出错时，把异常传递给订阅者。
                .doOnError(err -> {
                    // 标记流已经结束。
                    hasSentFinalResult.set(true);
                    // 发送异常给订阅者。
                    sink.tryEmitError(err);
                })
                // 订阅后强制最终答案请求开始执行。
                .subscribe();
    }

    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls, List<Message> messages, AtomicBoolean hasSentFinalResult, Runnable onComplete) {
        // completedCount 记录已经执行完成的工具调用数量。
        AtomicInteger completedCount = new AtomicInteger(0);
        // totalToolCalls 是本轮需要执行的工具调用总数。
        int totalToolCalls = toolCalls.size();

        // 遍历本轮所有工具调用，并把每个工具调用调度到弹性线程池。
        for (AssistantMessage.ToolCall tc : toolCalls) {
            // tc 是当前要执行的单个工具调用。
            Schedulers.boundedElastic().schedule(() -> {
                // 如果最终结果已经发送，则不再执行工具，只记录完成。
                if (hasSentFinalResult.get()) {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }

                // toolName 是模型请求调用的工具名。
                String toolName = tc.name();
                // argsJson 是模型提供的工具参数 JSON。
                String argsJson = tc.arguments();

                // callback 是根据 toolName 找到的工具实现。
                ToolCallback callback = findTool(toolName);
                // 找不到工具时，写入错误工具响应，并把本工具标记完成。
                if (callback == null) {
                    addErrorToolResponse(messages, tc, "工具未找到：" + toolName);
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }

                try {
                    // result 是工具执行后的原始返回对象。
                    Object result = callback.call(argsJson);
                    // resultStr 是工具返回的字符串形式，避免 null 造成响应构建异常。
                    String resultStr = Objects.toString(result, "");
                    // tr 是绑定 toolCall id 的工具响应，模型依靠 id 对齐请求和响应。
                    ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                            tc.id(), toolName, resultStr);
                    // 把工具执行结果作为 ToolResponseMessage 回填到上下文。
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(tr))
                            .build());
                } catch (Exception ex) {
                    // 工具执行异常时，写入错误响应，让模型可以基于错误继续处理。
                    addErrorToolResponse(messages, tc, "工具执行失败：" + ex.getMessage());
                } finally {
                    // 无论成功失败，都标记当前工具调用已完成。
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                }
            });
        }
    }

    private void completeToolCall(AtomicInteger completedCount, int total, Runnable onComplete) {
        // current 是当前已经完成的工具调用数量。
        int current = completedCount.incrementAndGet();
        // 所有工具都完成后，触发 onComplete 回调进入下一轮。
        if (current >= total) {
            onComplete.run();
        }
    }

    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        // tr 构造一个错误格式的工具响应，仍然绑定原 toolCall id。
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                "{ \"error\": \"" + errMsg + "\" }"
        );

        // 将错误工具响应写入上下文，保证模型下一轮能看到失败原因。
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

    private ToolCallback findTool(String name) {
        // 在已注册工具列表中按工具定义名称查找匹配项。
        return tools.stream()
                // t 是候选工具；工具定义里的 name 必须等于模型请求的 name。
                .filter(t -> t.getToolDefinition().name().equals(name))
                // 取第一个匹配工具。
                .findFirst()
                // 没有匹配工具时返回 null，由调用方构造错误工具响应。
                .orElse(null);
    }

    public static Builder builder() {
        // 创建 Builder，方便链式配置 SimpleReactAgent。
        return new Builder();
    }

    public static class Builder {
        // name 暂存要构建的 Agent 名称。
        private String name;
        // chatModel 暂存要使用的大模型实现，build 时必须存在。
        private ChatModel chatModel;
        // tools 暂存要注册给 Agent 的工具列表。
        private List<ToolCallback> tools;
        // systemPrompt 暂存业务系统提示词，默认空字符串表示不追加业务约束。
        private String systemPrompt = "";

        // maxReflectionRounds 暂存反思最大轮次。
        private int maxReflectionRounds;

        // maxRounds 暂存 ReAct 最大执行轮次。
        private int maxRounds;

        // advisors 暂存 Spring AI advisor 扩展列表。
        private List<Advisor> advisors;

        // chatMemory 暂存会话记忆实现。
        private ChatMemory chatMemory;

        public Builder chatMemory(ChatMemory chatMemory) {
            // 设置构建后 Agent 使用的会话记忆。
            this.chatMemory = chatMemory;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder name(String name) {
            // 设置 Agent 名称。
            this.name = name;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            // 设置底层聊天模型。
            this.chatModel = chatModel;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            // 将可变参数工具数组转换成 List 保存。
            this.tools = Arrays.asList(tools);
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            // 直接设置工具列表。
            this.tools = tools;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            // 直接设置 advisor 列表。
            this.advisors = advisors;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder advisors(Advisor... advisors) {
            // 将可变参数 advisor 数组转换成 List 保存。
            this.advisors = Arrays.asList(advisors);
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            // 设置业务系统提示词。
            this.systemPrompt = systemPrompt;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder maxReflectionRounds(int maxReflectionRounds) {
            // 设置最大反思轮次。
            this.maxReflectionRounds = maxReflectionRounds;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            // 设置最大 ReAct 执行轮次。
            this.maxRounds = maxRounds;
            // 返回当前 Builder，支持链式调用。
            return this;
        }

        public SimpleReactAgent build() {
            // chatModel 是必填依赖，没有模型就无法构建 Agent。
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            // 使用 Builder 中收集的配置创建 SimpleReactAgent 实例。
            return new SimpleReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, maxReflectionRounds);
        }
    }

    public static void main(String[] args) {
        // chatModel 从 ChatModelConfig 中读取配置并创建，包含 baseUrl、apiKey、model 等信息。
        ChatModel chatModel = ChatModelConfig.getChatModel();

        // toolCallbacks 将 WeatherTool 和 SearchTool 转成 Spring AI 可识别的工具回调数组。
        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherTool(), new SearchTool());

        // chatMemory 创建一个最多保留 20 条消息的窗口记忆。
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

        // agent 是本示例构建出的 ReAct Agent 实例。
        SimpleReactAgent agent = SimpleReactAgent.builder()
                // 设置 Agent 名称。
                .name("simple-agent")
                // 设置底层模型。
                .chatModel(chatModel)
                // 注册天气和搜索工具。
                .tools(toolCallbacks)
                // 注册会话记忆。
                .chatMemory(chatMemory)
                // 限制最多执行 5 轮 ReAct 循环。
                .maxRounds(5)
                // 设置业务角色提示词。
                .systemPrompt("你是专业的研究分析助手！")
                // 根据上面的链式配置创建 Agent。
                .build();

        // question 是示例问题，用来测试天气、搜索和综合分析能力。
        String question = """
                请你根据北京今天的天气、未来七天的天气趋势、以及上海今天的天气，并搜索北京天气的预警情况，生成一份不少于 600 字的综合分析报告。
                """;

//        // 非流式调用示例：直接打印完整答案。
        System.out.println(agent.call(question));

        // 流式调用示例：边生成边打印文本片段。
        agent.stream(question).doOnNext(chuck -> {
            // chuck 是当前收到的流式文本片段。
            System.out.print(chuck);
            // blockLast 会阻塞 main 线程直到流结束。
        }).blockLast();
    }
}
