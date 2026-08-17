package com.jaycong.dodo.agent; // 将手写 ReAct 行为测试放在核心 Agent 包中。

import com.jaycong.dodo.memory.InMemoryConversationMemory;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import com.jaycong.dodo.tool.ToolExecutionPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ManualReactAgentTest { // 定义 ReAct 正常决策、工具执行和消息回填的端到端单元测试。

    @Test
    void emitsCompleteFinalAnswerWhenModelDoesNotRequestTools() { // 验证模型直接回答时无需进入工具分支。
        ScriptedModel model = new ScriptedModel(new AssistantMessage("你好，我可以帮你。")); // 配置第一轮直接返回最终文本。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry(); // 创建独立任务注册表以检查生命周期清理。
        ManualReactAgent agent = agent(model, List.of(), tasks); // 组装不含工具的手写 Agent。

        StepVerifier.create(agent.stream("conversation-1", "你好")) // 订阅一次真实 Agent 事件流。
                .expectNext(AgentStreamEvent.text("你好，我可以帮你。")) // 断言阶段二以一个完整文本事件输出最终答案。
                .expectNext(AgentStreamEvent.complete()) // 断言最终文本后发送协议完成事件。
                .expectComplete() // 断言 Reactor 流随后正常关闭。
                .verify(Duration.ofSeconds(3)); // 设置上限，防止阻塞循环错误导致测试永久等待。
        assertThat(tasks.hasRunningTask("conversation-1")).isFalse(); // 断言正常完成后会话任务已从注册表释放。
    } // 结束直接回答正常路径测试。

    @Test
    void executesOneToolAndFeedsObservationBackToModel() { // 验证一次 Action/Observation 后模型能够生成最终答案。
        AssistantMessage toolDecision = decision(call("call-1", "weather", "{\"city\":\"北京\"}")); // 创建模型第一轮天气工具调用。
        ScriptedModel model = new ScriptedModel(toolDecision, new AssistantMessage("北京今天晴，25℃。")); // 配置工具后第二轮总结回答。
        ToolCallback weather = callback("weather", arguments -> "北京：晴，25℃"); // 创建返回稳定天气 Observation 的工具。
        ManualReactAgent agent = agent(model, List.of(weather), new InMemoryTaskRegistry()); // 组装包含天气工具的 Agent。

        StepVerifier.create(agent.stream("conversation-2", "北京天气如何？")) // 订阅需要一次工具调用的 Agent 流。
                .expectNext(AgentStreamEvent.toolStart("weather", "call-1", "{\"city\":\"北京\"}")) // 断言执行前先暴露 Action 事件。
                .expectNext(AgentStreamEvent.toolEnd("weather", "call-1", "北京：晴，25℃")) // 断言执行后暴露 Observation 事件。
                .expectNext(AgentStreamEvent.text("北京今天晴，25℃。")) // 断言模型基于 Observation 生成完整答案。
                .expectNext(AgentStreamEvent.complete()) // 断言工具路径也使用统一完成协议。
                .verifyComplete(); // 断言输出流正常关闭。
        assertThat(model.messageSnapshots).hasSize(2); // 断言模型在工具前后共决策两次。
        List<Message> secondRound = model.messageSnapshots.get(1); // 读取 Observation 回填后的第二轮消息快照。
        assertThat(secondRound).hasSize(4); // 断言上下文包含系统、用户、助手调用和工具响应四条消息。
        assertThat(secondRound.get(2)).isSameAs(toolDecision); // 断言模型原始 AssistantMessage 被完整加入历史。
        ToolResponseMessage toolResponse = (ToolResponseMessage) secondRound.get(3); // 读取紧随助手调用后的工具响应消息。
        assertThat(toolResponse.getResponses().getFirst().responseData()).isEqualTo("北京：晴，25℃"); // 断言 Observation 原样回填模型上下文。
    } // 结束单工具 ReAct 路径测试。

    @Test
    void executesMultipleToolCallsSequentiallyInModelOrder() { // 验证同一轮多个 Action 严格串行并保持响应顺序。
        AssistantMessage decision = decision( // 创建包含两个工具调用的单轮助手消息。
                call("call-1", "first", "{}"), // 把第一个工具调用放在列表首位。
                call("call-2", "second", "{}")); // 把第二个工具调用放在列表末位。
        ScriptedModel model = new ScriptedModel(decision, new AssistantMessage("两个工具都完成了。")); // 配置下一轮最终总结。
        List<String> executionOrder = new ArrayList<>(); // 保存真实回调发生顺序以验证没有并行执行。
        ToolCallback first = callback("first", arguments -> record(executionOrder, "first", "结果一")); // 创建记录顺序的第一个工具。
        ToolCallback second = callback("second", arguments -> record(executionOrder, "second", "结果二")); // 创建记录顺序的第二个工具。
        ManualReactAgent agent = agent(model, List.of(first, second), new InMemoryTaskRegistry()); // 组装多工具 Agent。

        StepVerifier.create(agent.stream("conversation-3", "依次执行两个工具")) // 订阅多工具 ReAct 事件流。
                .expectNext(AgentStreamEvent.toolStart("first", "call-1", "{}")) // 断言第一个工具先开始。
                .expectNext(AgentStreamEvent.toolEnd("first", "call-1", "结果一")) // 断言第一个工具结束后才处理下一个。
                .expectNext(AgentStreamEvent.toolStart("second", "call-2", "{}")) // 断言第二个工具随后开始。
                .expectNext(AgentStreamEvent.toolEnd("second", "call-2", "结果二")) // 断言第二个工具随后结束。
                .expectNext(AgentStreamEvent.text("两个工具都完成了。")) // 断言全部 Observation 回填后才生成最终答案。
                .expectNext(AgentStreamEvent.complete()) // 断言多工具路径正常完成。
                .verifyComplete(); // 断言事件流关闭且没有额外事件。
        assertThat(executionOrder).containsExactly("first", "second"); // 断言真实工具执行顺序与模型列表完全一致。
        ToolResponseMessage responses = (ToolResponseMessage) model.messageSnapshots.get(1).get(3); // 取得第二轮上下文中的聚合工具响应。
        assertThat(responses.getResponses()).extracting(ToolResponseMessage.ToolResponse::id) // 提取响应调用编号以检查关联顺序。
                .containsExactly("call-1", "call-2"); // 断言 ToolResponseMessage 保留模型调用顺序。
    } // 结束多工具串行执行测试。

    @Test
    void skipsRepeatedToolExecutionButStillReturnsObservationEvents() { // 验证相同工具签名不会反复产生真实副作用。
        AssistantMessage repeated = decision(call("call-1", "weather", " {\"city\":\"北京\"} ")); // 创建首轮带首尾空格的调用。
        AssistantMessage repeatedAgain = decision(call("call-2", "weather", "{\"city\":\"北京\"}")); // 创建下一轮语义相同的调用。
        ScriptedModel model = new ScriptedModel(repeated, repeatedAgain, new AssistantMessage("已根据第一次结果回答。")); // 配置重复调用后的最终回答。
        AtomicInteger executions = new AtomicInteger(); // 记录工具实现真正被调用的次数。
        ToolCallback weather = callback("weather", arguments -> { // 创建带可观察副作用计数的天气回调。
            executions.incrementAndGet(); // 每次真实进入回调时增加计数。
            return "北京：晴，25℃"; // 返回稳定天气 Observation。
        }); // 结束计数天气回调定义。
        ManualReactAgent agent = agent(model, List.of(weather), new InMemoryTaskRegistry()); // 组装重复调用测试 Agent。

        List<AgentStreamEvent> events = agent.stream("conversation-repeat", "重复查询") // 执行完整重复调用场景。
                .collectList() // 收集有限事件流以检查重复调用的两个生命周期事件。
                .block(Duration.ofSeconds(3)); // 设置等待上限并取得事件列表。

        assertThat(executions).hasValue(1); // 断言真实工具只执行第一次调用。
        assertThat(events).contains( // 断言重复调用仍有可观察的开始和结束事件。
                AgentStreamEvent.toolStart("weather", "call-2", "{\"city\":\"北京\"}"), // 保留模型第二次调用的独立编号。
                AgentStreamEvent.toolEnd("weather", "call-2", "工具调用已跳过：检测到重复调用")); // 用明确 Observation 告诉模型重复被阻止。
        assertThat(events).endsWith(AgentStreamEvent.text("已根据第一次结果回答。"), AgentStreamEvent.complete()); // 断言重复防护后循环仍能正常总结。
    } // 结束重复工具调用防护测试。

    @Test
    void disablesToolsAndForcesFinalAnswerAfterFourRounds() { // 验证达到循环上限后只允许模型总结已有 Observation。
        ScriptedModel model = new ScriptedModel( // 配置四轮工具调用和一次收尾回答。
                decision(call("call-1", "weather", "{\"round\":1}")), // 配置第一轮 Action。
                decision(call("call-2", "weather", "{\"round\":2}")), // 配置第二轮 Action。
                decision(call("call-3", "weather", "{\"round\":3}")), // 配置第三轮 Action。
                decision(call("call-4", "weather", "{\"round\":4}")), // 配置第四轮 Action。
                new AssistantMessage("根据现有工具结果完成总结。")); // 配置关闭工具后的最终文本。
        ToolCallback weather = callback("weather", arguments -> "观察结果 " + arguments); // 创建每轮均返回稳定文本的测试工具。
        ManualReactAgent agent = agent(model, List.of(weather), new InMemoryTaskRegistry()); // 组装达到轮次上限的 Agent。

        List<AgentStreamEvent> events = agent.stream("conversation-limit", "持续调用工具") // 执行直到强制收尾的完整循环。
                .collectList() // 收集全部事件以检查最终输出。
                .block(Duration.ofSeconds(3)); // 设置有限等待时间。

        assertThat(model.toolFlags).containsExactly(true, true, true, true, false); // 断言第五次决策关闭工具能力。
        assertThat(model.messageSnapshots.getLast().getLast()).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class); // 断言收尾前追加了明确用户指令。
        assertThat(model.messageSnapshots.getLast().getLast().getText()).contains("立即总结"); // 断言收尾指令要求基于已有观察作答。
        assertThat(events).endsWith(AgentStreamEvent.text("根据现有工具结果完成总结。"), AgentStreamEvent.complete()); // 断言上限不是错误，而是稳定最终回答。
    } // 结束最大轮次强制收尾测试。

    @Test
    void convertsEmptyFinalAnswerAndModelFailureIntoTerminalEvents() { // 验证不可恢复的模型结果使用统一错误终止协议。
        ManualReactAgent emptyAnswerAgent = agent( // 组装返回空白最终文本的 Agent。
                new ScriptedModel(new AssistantMessage("  ")), // 配置模型产生无效空白回答。
                List.of(), // 空白回答场景不需要工具。
                new InMemoryTaskRegistry()); // 使用独立任务注册表。
        ReactModelPort failingModel = (messages, toolsEnabled) -> { // 创建调用时直接失败的模型端口。
            throw new IllegalStateException("model unavailable"); // 模拟外部模型 API 故障。
        }; // 结束失败模型定义。
        ManualReactAgent failingAgent = new ManualReactAgent(failingModel, new AgentToolRegistry(List.of()), new InMemoryTaskRegistry(), new InMemoryConversationMemory()); // 组装使用独立空记忆的模型异常 Agent。

        StepVerifier.create(emptyAnswerAgent.stream("conversation-empty", "回答我")) // 订阅空白最终回答场景。
                .expectNext(AgentStreamEvent.error("模型未返回最终答案")) // 断言空白文本被视为不可恢复模型结果。
                .expectNext(AgentStreamEvent.complete()) // 断言错误之后仍发送统一完成事件。
                .verifyComplete(); // 断言流正常关闭。
        StepVerifier.create(failingAgent.stream("conversation-failure", "回答我")) // 订阅模型抛出异常场景。
                .expectNext(AgentStreamEvent.error("model unavailable")) // 断言模型异常消息被转换为业务错误事件。
                .expectNext(AgentStreamEvent.complete()) // 断言模型失败也发送完成事件。
                .verifyComplete(); // 断言流不会以 Reactor onError 泄漏框架异常。
    } // 结束空回答与模型异常测试。

    @Test
    void rejectsConcurrentConversationAndEmitsCancellationProtocol() throws Exception { // 验证同会话互斥和主动停止使用稳定生命周期协议。
        BlockingModel model = new BlockingModel(); // 创建会一直阻塞到被停止的模型端口。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry(); // 创建供测试直接触发停止操作的任务注册表。
        ManualReactAgent agent = agent(model, List.of(), tasks); // 组装无工具的阻塞 Agent。
        CompletableFuture<List<AgentStreamEvent>> firstRun = agent.stream("conversation-blocking", "等待") // 创建占用目标会话的第一条事件流。
                .collectList() // 收集主动取消后应产生的有限终止序列。
                .toFuture(); // 异步订阅，避免当前测试线程被模型阻塞。
        assertThat(model.entered.await(2, TimeUnit.SECONDS)).isTrue(); // 等待模型已进入阻塞调用，确保任务确实处于运行中。

        StepVerifier.create(agent.stream("conversation-blocking", "并发请求")) // 使用相同会话编号发起第二次订阅。
                .expectNext(AgentStreamEvent.error("conversation is already running")) // 断言重复任务在调用模型前被拒绝。
                .expectNext(AgentStreamEvent.complete()) // 断言并发冲突也有完整终止协议。
                .verifyComplete(); // 断言冲突流立即关闭。
        assertThat(tasks.cancel("conversation-blocking")).isTrue(); // 通过与 HTTP 停止接口相同的注册表入口取消首个任务。
        assertThat(firstRun.get(2, TimeUnit.SECONDS)).containsExactly( // 等待并检查仍连接客户端收到的取消序列。
                AgentStreamEvent.error("request cancelled"), // 主动停止先输出稳定取消原因。
                AgentStreamEvent.complete()); // 主动停止随后输出完成事件并关闭流。
        assertThat(tasks.hasRunningTask("conversation-blocking")).isFalse(); // 断言取消后会话任务已被释放。
    } // 结束并发保护和主动取消测试。

    @Test // 标记验证超时 Observation 仍会回填模型并完成运行的测试方法。
    void feedsTimeoutObservationBackToModelAndContinuesReactLoop() { // 验证超时是可恢复工具结果，而不是整个 Agent 的终止错误。
        AssistantMessage toolDecision = decision(call("call-timeout", "weather", "{}")); // 创建模型第一轮请求天气工具的 Action。
        ScriptedModel model = new ScriptedModel(toolDecision, new AssistantMessage("天气工具超时，我先给出替代说明。")); // 配置模型收到超时 Observation 后仍给出最终答案。
        ToolExecutionPort timedOutTool = (toolName, arguments) -> "工具执行超时：" + toolName; // 创建模拟限时执行器返回稳定超时 Observation 的端口。
        ManualReactAgent agent = agent(model, timedOutTool, new InMemoryTaskRegistry()); // 组装使用专用限时端口的 ReAct Agent。

        StepVerifier.create(agent.stream("conversation-timeout", "查询天气")) // 订阅包含一次超时工具调用的真实 Agent 流。
                .expectNext(AgentStreamEvent.toolStart("weather", "call-timeout", "{}")) // 断言真实工具调用前仍按原协议发送开始事件。
                .expectNext(AgentStreamEvent.toolEnd("weather", "call-timeout", "工具执行超时：weather")) // 断言超时作为可观察结束 Observation 发给客户端。
                .expectNext(AgentStreamEvent.text("天气工具超时，我先给出替代说明。")) // 断言模型可以基于超时 Observation 继续完成回答。
                .expectNext(AgentStreamEvent.complete()) // 断言可恢复超时路径仍使用正常完成协议。
                .verifyComplete(); // 断言整个事件流正常关闭。
        ToolResponseMessage responses = (ToolResponseMessage) model.messageSnapshots.get(1).get(3); // 取得第二轮模型调用前聚合的工具响应消息。
        assertThat(responses.getResponses().getFirst().responseData()).isEqualTo("工具执行超时：weather"); // 断言 SSE 展示与模型上下文回填使用同一超时文本。
    } // 结束超时 Observation 回填测试。

    @Test // 标记验证取消等待工具时不会产生迟到工具事件的测试方法。
    void cancellationDuringToolWaitEmitsOnlyCancellationProtocol() throws Exception { // 验证停止请求优先于工具迟到结果和后续模型调用。
        AssistantMessage toolDecision = decision(call("call-blocking", "weather", "{}")); // 创建会进入阻塞工具执行的第一轮 Action。
        ScriptedModel model = new ScriptedModel(toolDecision, new AssistantMessage("不应回答")); // 配置理论上只能在取消失败后才会使用的最终回答。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry(); // 创建供测试主动取消正在运行任务的注册表。
        CountDownLatch entered = new CountDownLatch(1); // 创建等待 Agent 已进入工具端口的同步信号。
        CountDownLatch interrupted = new CountDownLatch(1); // 创建确认 Agent 外层中断已传递到端口的同步信号。
        ToolExecutionPort blockingTool = (toolName, arguments) -> { // 创建会阻塞至被任务停止中断的测试工具端口。
            entered.countDown(); // 标记 Agent 已开始等待本次工具执行。
            try { // 进入可由 boundedElastic 工作订阅 dispose 中断的等待区域。
                new CountDownLatch(1).await(); // 持续阻塞，直到停止请求打断当前工作线程。
                return "不应返回"; // 为编译器提供理论不可达的返回值。
            } catch (InterruptedException error) { // 接收任务取消传播到 Agent 工作者的中断。
                interrupted.countDown(); // 通知测试线程端口已观察到中断。
                Thread.currentThread().interrupt(); // 恢复中断标记以保持协作取消语义。
                throw new IllegalStateException("tool interrupted", error); // 抛出异常，验证 Agent 不会把它转换成迟到 tool_end。
            } // 结束阻塞工具端口的中断处理分支。
        }; // 结束阻塞工具端口定义。
        ManualReactAgent agent = agent(model, blockingTool, tasks); // 组装使用可取消端口的 Agent。

        CompletableFuture<List<AgentStreamEvent>> events = agent.stream("conversation-tool-cancel", "等待工具") // 异步订阅正在等待工具的事件流。
                .collectList() // 收集取消后应立即结束的稳定事件序列。
                .toFuture(); // 转换为 Future，避免测试线程阻塞 Agent 工作线程。
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); // 确认工具已经开始后再发起停止，避免取消尚未注册的任务。
        assertThat(tasks.cancel("conversation-tool-cancel")).isTrue(); // 触发与 HTTP 停止接口相同的任务取消入口。
        assertThat(events.get(2, TimeUnit.SECONDS)).containsExactly( // 读取仍连接客户端收到的完整取消序列。
                AgentStreamEvent.toolStart("weather", "call-blocking", "{}"), // 断言已经开始的真实工具调用保留其既有可观察事件。
                AgentStreamEvent.error("request cancelled"), // 断言取消随后输出既有稳定错误事件。
                AgentStreamEvent.complete()); // 断言取消后只输出完成事件，绝不发送迟到 tool_end 或文本事件。
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue(); // 断言停止也中断了正在等待的工具端口。
        assertThat(model.messageSnapshots).hasSize(1); // 断言取消后没有再用工具结果请求第二轮模型。
    } // 结束等待工具取消测试。

    @Test
    void rejectsOversizedMandatoryContextBeforeCallingModelOrPersistingMemory() { // 验证当前问题与系统提示超过固定预算时不会启动模型或成功持久化。
        ScriptedModel model = new ScriptedModel(new AssistantMessage("不应调用模型")); // 配置一条理论上不应被消费的模型回答。
        InMemoryConversationMemory memory = new InMemoryConversationMemory(); // 创建可检查是否被错误写入的内存记忆。
        ManualReactAgent agent = new ManualReactAgent(model, new AgentToolRegistry(List.of()), new InMemoryTaskRegistry(), memory); // 组装使用真实记忆端口的 Agent。
        String oversizedQuestion = "问题".repeat(8_000); // 创建足以使系统提示和当前问题超过两千估算 Token 的输入。

        StepVerifier.create(agent.stream("conversation-budget-overflow", oversizedQuestion)) // 订阅必需上下文超预算的真实 Agent 流。
                .expectNext(AgentStreamEvent.error("上下文预算不足：系统提示和当前问题已超过 2000 Token")) // 断言返回稳定预算错误而非截断后继续请求模型。
                .expectNext(AgentStreamEvent.complete()) // 断言拒绝路径仍按 SSE 协议完成。
                .verifyComplete(); // 断言流正常关闭而不泄漏 Reactor 异常。
        assertThat(model.messageSnapshots).isEmpty(); // 断言模型端口从未接收到不安全的超大上下文。
        assertThat(memory.get("conversation-budget-overflow")).isEmpty(); // 断言失败拒绝路径没有留下成功问答记忆。
    } // 结束必需上下文超预算保护测试。

    private ManualReactAgent agent(ReactModelPort model, List<ToolCallback> callbacks, InMemoryTaskRegistry tasks) { // 统一组装支持任意假模型端口的手写 Agent。
        return new ManualReactAgent(model, new AgentToolRegistry(callbacks), tasks, new InMemoryConversationMemory()); // 注入脚本模型、真实注册表、任务生命周期组件和独立空记忆。
    } // 结束测试 Agent 工厂方法。

    private ManualReactAgent agent(ReactModelPort model, ToolExecutionPort toolExecutor, InMemoryTaskRegistry tasks) { // 统一组装可注入限时或阻塞端口的 ReAct Agent。
        return new ManualReactAgent(model, toolExecutor, tasks, new InMemoryConversationMemory()); // 注入模型、测试执行端口、任务注册表和独立空记忆。
    } // 结束测试执行端口 Agent 工厂方法。

    private AssistantMessage decision(AssistantMessage.ToolCall... calls) { // 创建只有工具调用而没有最终文本的助手决策。
        return AssistantMessage.builder().content("").toolCalls(List.of(calls)).build(); // 使用官方 Builder 保存有序 ToolCall 列表。
    } // 结束工具决策工厂方法。

    private AssistantMessage.ToolCall call(String id, String name, String arguments) { // 创建一个 Spring AI 工具调用记录。
        return new AssistantMessage.ToolCall(id, "function", name, arguments); // 固定 function 类型并保留调用编号、名称和原始参数。
    } // 结束工具调用工厂方法。

    private ToolCallback callback(String name, ToolExecutor executor) { // 创建执行测试逻辑的最小工具回调。
        return new ToolCallback() { // 返回符合 Spring AI 协议的匿名回调对象。
            @Override
            public ToolDefinition getToolDefinition() { // 提供模型声明和注册表索引所需的定义。
                return ToolDefinition.builder().name(name).description("测试工具").inputSchema("{\"type\":\"object\"}").build(); // 构建名称唯一的最小工具定义。
            } // 结束工具定义方法。

            @Override
            public String call(String arguments) { // 接收模型原始 JSON 参数。
                return executor.execute(arguments); // 委托给当前测试场景的真实执行函数。
            } // 结束工具调用方法。
        }; // 结束匿名回调对象。
    } // 结束测试工具工厂方法。

    private String record(List<String> order, String name, String result) { // 记录工具执行顺序并返回指定 Observation。
        order.add(name); // 把本次真实执行的工具名追加到顺序列表。
        return result; // 返回当前工具的稳定结果。
    } // 结束执行顺序记录方法。

    private static final class ScriptedModel implements ReactModelPort { // 定义按队列返回预设助手消息的假模型。
        private final Queue<AssistantMessage> decisions; // 保存尚未返回的脚本决策队列。
        private final List<List<Message>> messageSnapshots = new ArrayList<>(); // 保存每轮模型收到的不可变消息快照。
        private final List<Boolean> toolFlags = new ArrayList<>(); // 保存每轮是否向模型启用工具的状态。

        private ScriptedModel(AssistantMessage... decisions) { // 接收测试场景按时间顺序配置的模型决策。
            this.decisions = new ArrayDeque<>(List.of(decisions)); // 复制为可依次移除的队列。
        } // 结束脚本模型构造方法。

        @Override
        public AssistantMessage decide(List<Message> messages, boolean toolsEnabled) { // 根据脚本返回下一条模型决策。
            messageSnapshots.add(List.copyOf(messages)); // 在返回前保存本轮完整上下文快照。
            toolFlags.add(toolsEnabled); // 记录本轮工具开关以验证强制收尾行为。
            return decisions.remove(); // 移除并返回队首助手消息，额外调用会让测试立即失败。
        } // 结束脚本模型决策方法。
    } // 结束脚本模型类型。

    private static final class BlockingModel implements ReactModelPort { // 定义用于制造停止竞争的可中断阻塞模型。
        private final CountDownLatch entered = new CountDownLatch(1); // 在模型调用开始时通知测试线程可以发起取消。

        @Override
        public AssistantMessage decide(List<Message> messages, boolean toolsEnabled) { // 模拟无法立刻返回的同步模型 API。
            entered.countDown(); // 标记 boundedElastic 工作者已经进入模型调用。
            try { // 等待任务订阅被 dispose 后产生线程中断。
                new CountDownLatch(1).await(); // 使用永不主动释放的闩锁保持模型调用阻塞。
                return new AssistantMessage("不应返回"); // 理论上不可到达，满足编译器返回值要求。
            } catch (InterruptedException error) { // worker.dispose 会中断当前 boundedElastic 任务。
                Thread.currentThread().interrupt(); // 恢复中断标记以遵守 Java 并发约定。
                throw new IllegalStateException("blocking model interrupted", error); // 把中断转换成适配器可能产生的运行时失败。
            } // 结束阻塞等待异常处理。
        } // 结束阻塞模型决策方法。
    } // 结束阻塞测试模型类型。

    @FunctionalInterface
    private interface ToolExecutor { // 定义测试工具可用 Lambda 提供的执行契约。
        String execute(String arguments); // 根据原始 JSON 参数返回 Observation。
    } // 结束测试工具执行接口。
} // 结束手写 ReAct Agent 测试类。
