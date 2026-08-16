package com.jaycong.dodo.agent; // 将手写 ReAct 行为测试放在核心 Agent 包中。

import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
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

    private ManualReactAgent agent(ScriptedModel model, List<ToolCallback> callbacks, InMemoryTaskRegistry tasks) { // 统一组装测试所需的手写 Agent。
        return new ManualReactAgent(model, new AgentToolRegistry(callbacks), tasks); // 注入脚本模型、真实注册表和独立任务生命周期组件。
    } // 结束测试 Agent 工厂方法。

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

        private ScriptedModel(AssistantMessage... decisions) { // 接收测试场景按时间顺序配置的模型决策。
            this.decisions = new ArrayDeque<>(List.of(decisions)); // 复制为可依次移除的队列。
        } // 结束脚本模型构造方法。

        @Override
        public AssistantMessage decide(List<Message> messages, boolean toolsEnabled) { // 根据脚本返回下一条模型决策。
            messageSnapshots.add(List.copyOf(messages)); // 在返回前保存本轮完整上下文快照。
            return decisions.remove(); // 移除并返回队首助手消息，额外调用会让测试立即失败。
        } // 结束脚本模型决策方法。
    } // 结束脚本模型类型。

    @FunctionalInterface
    private interface ToolExecutor { // 定义测试工具可用 Lambda 提供的执行契约。
        String execute(String arguments); // 根据原始 JSON 参数返回 Observation。
    } // 结束测试工具执行接口。
} // 结束手写 ReAct Agent 测试类。
