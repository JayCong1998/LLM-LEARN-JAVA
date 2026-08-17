package com.jaycong.dodo.agent; // 将手写 ReAct 编排器放在 Agent 核心包中。

import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.memory.ConversationTurn;
import com.jaycong.dodo.trace.SuccessfulAgentRun;
import com.jaycong.dodo.trace.SuccessfulAgentRunPersistence;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import com.jaycong.dodo.tool.ToolExecutionPort;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * 显式实现“模型决策 -> 人工执行工具 -> Observation 回填 -> 再次决策”的 ReAct 循环。
 * 模型同步调用和本地工具都在 boundedElastic 工作线程串行执行，HTTP 层只消费稳定 AgentStreamEvent。
 */
@Service
public class ManualReactAgent { // 定义阶段二用于教学和后续扩展的核心 Agent 编排器。

    private static final int MAX_DECISION_ROUNDS = 4; // 限制允许工具的模型决策最多四轮，防止无限循环。
    private static final String SYSTEM_PROMPT = "你是一个可靠的助手。需要时调用工具，并基于工具结果给出最终答案。"; // 固定最小系统规则并避免要求输出内部思维链。
    private final ReactModelPort model; // 保存一次生成助手决策的抽象模型端口。
    private final ToolExecutionPort toolExecutor; // 保存封装超时和取消传播的工具执行端口。
    private final InMemoryTaskRegistry tasks; // 保存会话互斥、停止入口和工作订阅句柄。
    private final ConversationMemory memory; // 保存按会话读取跨请求历史的抽象记忆端口。
    private final SuccessfulAgentRunPersistence successfulRuns; // 保存只接收完整成功运行的轨迹持久化端口。

    public ManualReactAgent(ReactModelPort model, AgentToolRegistry tools, InMemoryTaskRegistry tasks, ConversationMemory memory) { // 为既有独立教学测试保留四参数构造入口。
        this(model, tools::execute, tasks, memory, run -> memory.append(run.conversationId(), new ConversationTurn(run.question(), run.answer()))); // 兼容入口继续直连注册表，避免既有测试被生产超时策略干扰。
    } // 结束兼容测试构造方法。

    public ManualReactAgent(ReactModelPort model, ToolExecutionPort toolExecutor, InMemoryTaskRegistry tasks, ConversationMemory memory) { // 为需要注入受控执行端口的 Agent 测试提供四参数入口。
        this(model, toolExecutor, tasks, memory, run -> memory.append(run.conversationId(), new ConversationTurn(run.question(), run.answer()))); // 复用完整构造器并保留既有测试的问答持久化断言。
    } // 结束测试执行端口构造方法。

    public ManualReactAgent(ReactModelPort model, AgentToolRegistry tools, InMemoryTaskRegistry tasks, ConversationMemory memory, SuccessfulAgentRunPersistence successfulRuns) { // 为既有完整运行轨迹测试保留注册表形式的五参数组装入口。
        this(model, tools::execute, tasks, memory, successfulRuns); // 将旧注册表适配为执行端口，避免测试意外引入真实三秒等待。
    } // 结束注册表形式的完整测试构造方法。

    @Autowired
    public ManualReactAgent( // 通过构造器显式声明 ReAct 循环的四个边界依赖。
            ReactModelPort model, // 接收可替换的模型决策端口。
            ToolExecutionPort toolExecutor, // 接收负责超时和取消传播的工具执行端口。
            InMemoryTaskRegistry tasks, // 接收进程内任务生命周期注册表。
            ConversationMemory memory, // 接收只读取跨请求问答历史的端口。
            SuccessfulAgentRunPersistence successfulRuns) { // 接收只持久化完整成功运行的端口并结束参数列表。
        this.model = model; // 保存模型端口供每轮同步决策使用。
        this.toolExecutor = toolExecutor; // 保存执行端口供 Action 限时运行和 Observation 生成使用。
        this.tasks = tasks; // 保存任务注册表供并发保护和资源释放使用。
        this.memory = memory; // 保存记忆端口供每次运行开始时读取一次历史快照。
        this.successfulRuns = successfulRuns; // 保存轨迹端口供最终答案成功后一次性提交完整记录。
    } // 结束手写 Agent 构造方法。

    /**
     * 为一条用户消息创建延迟执行的 ReAct 事件流。
     * 每次订阅都会创建独立上下文和单订阅 Sink，同一 conversationId 则由任务注册表原子拒绝并发运行。
     */
    public Flux<AgentStreamEvent> stream(String conversationId, String message) { // 定义 Agent 运行，但在客户端订阅前不调用模型。
        return Flux.defer(() -> { // 为每个真实 HTTP 订阅隔离全部可变状态。
            ReactRunContext context = new ReactRunContext( // 创建只属于本次请求的 ReAct 上下文。
                    List.of(), // 暂以空消息初始化，历史读取和消息装配将在阻塞工作线程中完成。
                    MAX_DECISION_ROUNDS); // 应用固定四轮工具决策上限。
            Sinks.Many<AgentStreamEvent> output = Sinks.many().unicast().onBackpressureBuffer(); // 创建单客户端可写事件通道并缓冲短暂消费延迟。
            Runnable onCancel = () -> { // 定义停止接口成功后需要执行的稳定协议收尾回调。
                context.markCancelled(); // 先设置取消标记，让阻塞模型迟到结果和异常不再按普通路径输出。
                if (context.tryFinish()) { // 只有取消率先取得终止权时才发送一次终止序列。
                    output.tryEmitNext(AgentStreamEvent.error("request cancelled")); // 向仍连接的客户端明确说明任务被主动停止。
                    output.tryEmitNext(AgentStreamEvent.complete()); // 发送协议完成事件使前端退出运行状态。
                    output.tryEmitComplete(); // 关闭 Reactor 流并释放 SSE 下游资源。
                } // 结束取消终止单次执行保护分支。
            }; // 结束主动取消协议回调定义。
            if (!tasks.register(conversationId, onCancel)) { // 在调用模型前原子占用会话编号，避免重复任务并行。
                return Flux.just( // 为并发冲突创建无需后台工作的有限事件流。
                        AgentStreamEvent.error("conversation is already running"), // 输出与阶段一兼容的会话冲突错误。
                        AgentStreamEvent.complete()); // 输出完成事件让前端退出加载状态。
            } // 结束同会话并发保护分支。

            /*
             * ChatClient.call 是阻塞操作，因此整个 while 循环必须离开 WebFlux 事件线程。
             * 一个运行只使用一个 boundedElastic 工作者，工具调用自然保持模型给出的顺序。
             */
            var worker = Mono.fromRunnable(() -> runLoop(conversationId, message, context, output, System.nanoTime())) // 在线程开始前记录单调时钟起点并包装阻塞循环。
                    .subscribeOn(Schedulers.boundedElastic()) // 将模型阻塞调用调度到有界弹性线程池。
                    .subscribe(); // 启动后台循环并取得停止接口可以 dispose 的订阅句柄。
            tasks.attach(conversationId, worker); // 把工作订阅绑定到已注册任务，处理注册后立即取消的竞争。

            return output.asFlux().doFinally(signal -> { // 暴露只读输出流并统一处理客户端断开或正常终止。
                if (signal == SignalType.CANCEL) { // 下游取消表示浏览器断开，后台工作不应继续消耗模型资源。
                    tasks.cancel(conversationId); // 移除任务、dispose 工作订阅并设置上下文取消标记。
                } else { // 正常完成时 runLoop 已经完成协议输出，只需幂等清理注册表。
                    tasks.complete(conversationId); // 确保会话编号最终可以被下一次请求复用。
                } // 结束最终信号类型分支。
            }); // 结束输出事件流的最终清理配置。
        }); // 结束每订阅一次创建一次运行状态的 defer 工厂。
    } // 结束 Agent 事件流创建方法。

    /**
     * 在同一个工作线程中执行正常 ReAct 循环。
     * 每轮先保存 AssistantMessage；存在 ToolCall 时顺序执行并聚合 ToolResponseMessage，否则输出最终答案。
     */
    private void runLoop( // 定义只由 boundedElastic 工作者调用的同步状态机。
            String conversationId, // 接收任务注册与清理使用的会话编号。
            String currentUserMessage, // 接收本次 HTTP 请求的用户问题，供历史之后追加。
            ReactRunContext context, // 接收本次运行独享的消息和状态上下文。
            Sinks.Many<AgentStreamEvent> output, // 接收向 SSE 下游写入事件的单订阅 Sink。
            long startedAtNanos) { // 接收本次 boundedElastic 工作者的单调计时起点。
        try { // 捕获模型边界的意外异常并保证任务能够关闭。
            initializeMessages(conversationId, currentUserMessage, context); // 在阻塞工作线程读取快照并按角色顺序构造初始上下文。
            if (context.isCancelled()) { // 历史读取期间客户端可能已经主动停止当前任务。
                return; // 丢弃已经加载的快照，不再调用模型或产生普通终止事件。
            } // 结束历史加载后的取消保护分支。
            while (!context.isCancelled() && context.tryStartDecisionRound()) { // 在未取消且未超过四轮时继续请求下一步决策。
                AssistantMessage assistant = model.decide(context.messagesWithinBudget(), true); // 仅携带预算内快照并允许模型选择工具，完整历史仍留在运行上下文。
                if (context.isCancelled()) { // 模型阻塞返回时任务可能已被停止，因此结果落库前再次检查。
                    return; // 丢弃取消后迟到的模型结果，不向已终止客户端继续输出。
                } // 结束模型返回后的取消保护分支。
                context.addMessage(assistant); // 把包含文本或 ToolCall 的原始助手消息加入历史。
                if (!assistant.hasToolCalls()) { // 没有 Action 表示模型认为已经可以给出最终答案。
                    finishSuccessfully(conversationId, currentUserMessage, context, output, assistant.getText(), startedAtNanos); // 持久化完整轨迹后输出最终文本并执行唯一正常收尾。
                    return; // 终止循环，避免最终答案之后再次请求模型。
                } // 结束最终答案判断分支。
                if (executeToolCalls(context, output, assistant.getToolCalls(), startedAtNanos)) { // 串行执行本轮全部 Action，并识别工具等待期间发生的取消。
                    return; // 取消已由注册表回调完成协议收尾，禁止继续回填或再次调用模型。
                } // 结束工具执行取消保护分支。
            } // 结束允许工具的模型决策循环。
            if (!context.isCancelled()) { // 正常路径不应静默耗尽轮次，因此先排除主动取消。
                context.addMessage(new UserMessage("工具调用已达到上限，请基于已有观察立即总结并给出最终答案，不要再调用工具。")); // 向模型明确追加只总结已有信息的收尾指令。
                AssistantMessage finalAssistant = model.decide(context.messagesWithinBudget(), false); // 使用同一预算规则关闭工具并发起最后一次同步决策。
                if (context.isCancelled()) { // 收尾模型调用返回后再次检查任务是否已被并发停止。
                    return; // 丢弃取消之后迟到的强制总结结果。
                } // 结束收尾模型返回后的取消保护分支。
                context.addMessage(finalAssistant); // 把最后助手消息加入完整运行历史。
                if (finalAssistant.hasToolCalls()) { // 防御不遵守工具关闭约束的异常模型响应。
                    finishWithError(conversationId, context, output, "模型在强制收尾阶段仍请求工具"); // 使用稳定错误终止而不执行第五轮工具。
                    return; // 阻止异常 ToolCall 进入实际工具执行路径。
                } // 结束强制收尾工具调用保护分支。
                finishSuccessfully(conversationId, currentUserMessage, context, output, finalAssistant.getText(), startedAtNanos); // 持久化完整轨迹后输出基于已有 Observation 的最终答案。
            } // 结束最大轮次强制收尾分支。
        } catch (Exception error) { // 捕获模型适配器或循环代码抛出的不可恢复异常。
            finishWithError(conversationId, context, output, errorMessage(error)); // 转换为稳定错误和完成事件，避免 SSE 悬挂。
        } // 结束同步 ReAct 循环异常边界。
    } // 结束 ReAct 主循环方法。

    /**
     * 在每次运行开始时只读取一次跨请求历史，并转换成 Spring AI 的角色消息。
     * 工具调用和工具响应不会出现在 ConversationTurn 中，因此不会被错误带入下一次 HTTP 请求。
     */
    private void initializeMessages( // 定义跨请求历史到单次运行上下文的转换边界。
            String conversationId, // 接收需要读取历史的会话编号。
            String currentUserMessage, // 接收必须放在全部历史之后的当前用户问题。
            ReactRunContext context) { // 接收需要按时间顺序追加消息的本次运行上下文。
        List<ConversationTurn> historySnapshot = memory.get(conversationId); // 只读取一次不可变快照，隔离运行期间的追加或清空。
        context.addMessage(new SystemMessage(SYSTEM_PROMPT)); // 始终先加入系统规则，建立整段模型上下文的行为边界。
        for (ConversationTurn turn : historySnapshot) { // 按窗口保存的时间顺序回放每轮完整问答。
            context.addMessage(new UserMessage(turn.userContent())); // 把历史问题恢复成模型可识别的用户角色消息。
            context.addMessage(new AssistantMessage(turn.assistantContent())); // 把对应最终回答恢复成助手角色消息并保持问答配对。
        } // 结束历史轮次回放循环。
        UserMessage currentMessage = new UserMessage(currentUserMessage); // 创建本轮唯一的当前用户消息对象以建立不可裁剪边界。
        context.setCurrentUserMessage(currentMessage); // 在加入完整历史前显式标记当前问题，避免与历史同文本问题混淆。
        context.addMessage(currentMessage); // 最后加入本次问题，使模型明确当前需要处理的输入。
    } // 结束初始消息装配方法。

    private boolean executeToolCalls( // 定义一轮内按顺序执行全部工具调用并报告是否因取消提前结束。
            ReactRunContext context, // 接收需要登记消息历史的本轮上下文。
            Sinks.Many<AgentStreamEvent> output, // 接收工具开始和结束事件的输出通道。
            List<AssistantMessage.ToolCall> toolCalls, // 接收模型给出的有序工具调用列表。
            long startedAtNanos) { // 接收本次运行的单调计时起点。
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(); // 创建与调用顺序一致的 Observation 响应列表。
        for (AssistantMessage.ToolCall toolCall : toolCalls) { // 串行遍历工具调用，避免共享资源并发和响应乱序。
            String observation; // 声明本次调用最终需要展示并回填模型的 Observation。
            if (context.markToolExecution(toolCall.name(), toolCall.arguments())) { // 只有标准化签名第一次出现时才允许真实执行。
                context.recordFirstResponseTimeMillisIfAbsent(elapsedMillis(startedAtNanos)); // 在首个真实工具 Action 对外可见前冻结首响应耗时。
                output.tryEmitNext(AgentStreamEvent.toolStart(toolCall.name(), toolCall.id(), toolCall.arguments())); // 在真实执行前向客户端暴露 Action。
                observation = toolExecutor.execute(toolCall.name(), toolCall.arguments(), (attempt, delayMillis) -> { // 经过可靠性端口执行工具，并接收只含安全元数据的重试计划。
                    if (!context.isCancelled()) { // 取消已经取得终止权时不能继续向客户端发送迟到重试事件。
                        output.tryEmitNext(AgentStreamEvent.toolRetry(toolCall.name(), toolCall.id(), attempt, delayMillis)); // 将重试通知转换为可关联的 SSE 事件。
                    } // 结束重试通知取消保护分支。
                }); // 结束带重试通知的工具执行调用。
            } else { // 相同工具名和清理首尾空格后的参数已经在本次运行中执行过。
                output.tryEmitNext(AgentStreamEvent.toolStart(toolCall.name(), toolCall.id(), toolCall.arguments())); // 保持重复调用原有可观察 SSE 协议。
                observation = "工具调用已跳过：检测到重复调用"; // 生成稳定防循环 Observation，避免重复副作用。
            } // 结束工具签名首次执行判断分支。
            if (context.isCancelled()) { // 工具等待结束前停止请求可能已经取得终止权。
                return true; // 禁止向已取消运行发送迟到 tool_end、回填 Observation 或继续模型循环。
            } // 结束工具返回后的取消保护分支。
            output.tryEmitNext(AgentStreamEvent.toolEnd(toolCall.name(), toolCall.id(), observation)); // 在未取消时输出可关联的工具结束事件。
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), observation)); // 用原调用编号创建模型可理解的工具响应。
        } // 结束本轮有序工具调用循环。
        context.addMessage(ToolResponseMessage.builder().responses(responses).build()); // 把全部 Observation 聚合成紧随 AssistantMessage 的协议消息。
        return false; // 报告本轮工具调用正常完成，允许循环进入下一次模型决策。
    } // 结束工具调用执行和回填方法。

    private void finishSuccessfully( // 定义最终答案的唯一正常终止序列。
            String conversationId, // 接收需要从任务注册表释放的会话编号。
            String currentUserMessage, // 接收需要与最终答案配对保存的本次用户问题。
            ReactRunContext context, // 接收保护单次终止的运行上下文。
            Sinks.Many<AgentStreamEvent> output, // 接收最终文本和完成事件的输出通道。
            String answer, // 接收模型生成的完整最终回答。
            long startedAtNanos) { // 接收本次运行的单调计时起点。
        if (answer == null || answer.isBlank()) { // 最终文本为空时前端和用户都无法得到有效结果。
            finishWithError(conversationId, context, output, "模型未返回最终答案"); // 将无效模型结果转换成稳定错误终止协议。
            return; // 停止正常完成流程，避免继续发送空 text 事件。
        } // 结束空最终答案保护分支。
        if (context.tryFinish()) { // 只有第一个终止路径可以输出并关闭协议流。
            try { // 在展示最终答案前提交完整问答，保证客户端看到的成功结果已经进入跨请求记忆。
                context.recordFirstResponseTimeMillisIfAbsent(elapsedMillis(startedAtNanos)); // 直接回答时在最终 text 可见前冻结首响应耗时。
                successfulRuns.persist(new SuccessfulAgentRun(conversationId, currentUserMessage, answer, context.executedToolNames(), context.firstResponseTimeMillis(), elapsedMillis(startedAtNanos), "manual-react")); // 一次性写入问答与安全轨迹，禁止分步形成半成品记录。
            } catch (Exception error) { // 捕获记忆实现的保存异常并转换为稳定终止协议。
                /*
                 * 当前分支已经通过 tryFinish 取得唯一终止权，不能再调用 finishWithError。
                 * finishWithError 内部会执行第二次 tryFinish 并失败，最终导致客户端收不到任何错误和完成事件。
                 */
                tasks.complete(conversationId); // 轨迹写入失败后仍释放会话占用，允许用户随后重试。
                output.tryEmitNext(AgentStreamEvent.error("运行轨迹保存失败：" + errorMessage(error))); // 直接发送可诊断的保存失败事件，并明确区分模型错误。
                output.tryEmitNext(AgentStreamEvent.complete()); // 保存失败后仍发送协议完成事件供前端统一收尾。
                output.tryEmitComplete(); // 关闭 Reactor 流并触发幂等资源清理。
                return; // 阻止未成功保存的答案继续作为 text 事件发给客户端。
            } // 结束记忆保存异常处理分支。
            tasks.complete(conversationId); // 完整运行已写入后释放会话占用，使任务状态与即将结束的流一致。
            output.tryEmitNext(AgentStreamEvent.text(answer)); // 阶段二使用单个文本事件发送完整最终答案。
            output.tryEmitNext(AgentStreamEvent.complete()); // 发送显式协议完成事件供前端统一收尾。
            output.tryEmitComplete(); // 关闭 Reactor 流并触发最终清理钩子。
        } // 结束正常终止单次执行保护分支。
    } // 结束正常终止方法。

    private void finishWithError( // 定义不可恢复模型或循环失败的统一终止序列。
            String conversationId, // 接收需要释放的会话编号。
            ReactRunContext context, // 接收单次终止原子闸门。
            Sinks.Many<AgentStreamEvent> output, // 接收错误和完成事件的输出通道。
            String message) { // 接收已经转换为稳定文本的失败原因。
        if (context.tryFinish()) { // 防止异常和其他终止路径竞争时重复输出。
            tasks.complete(conversationId); // 从运行任务集合移除当前会话。
            output.tryEmitNext(AgentStreamEvent.error(message)); // 先输出可展示的错误原因。
            output.tryEmitNext(AgentStreamEvent.complete()); // 再输出统一协议完成事件。
            output.tryEmitComplete(); // 最后关闭 Reactor 事件流释放 SSE 连接。
        } // 结束异常终止单次执行保护分支。
    } // 结束异常终止方法。

    private String errorMessage(Exception error) { // 从任意异常生成非空的稳定错误文本。
        if (error.getMessage() == null || error.getMessage().isBlank()) { // 检查异常是否缺少可读消息。
            return error.getClass().getSimpleName(); // 回退到异常类型，避免向客户端发送 null。
        } // 结束异常消息回退分支。
        return error.getMessage(); // 保留具体模型或循环失败说明供学习和诊断。
    } // 结束异常文本转换方法。

    private long elapsedMillis(long startedAtNanos) { // 将单调纳秒起点转换为当前经过的非负毫秒数。
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos); // 使用单调时钟差值避免系统时间调整污染性能指标。
    } // 结束单调耗时计算方法。
} // 结束手写 ReAct Agent 定义。
