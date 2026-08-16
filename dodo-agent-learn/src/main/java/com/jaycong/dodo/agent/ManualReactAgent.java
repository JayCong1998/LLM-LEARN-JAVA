package com.jaycong.dodo.agent; // 将手写 ReAct 编排器放在 Agent 核心包中。

import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
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
    private final AgentToolRegistry tools; // 保存工具声明和实际执行共用的注册表。
    private final InMemoryTaskRegistry tasks; // 保存会话互斥、停止入口和工作订阅句柄。

    public ManualReactAgent( // 通过构造器显式声明 ReAct 循环的三个边界依赖。
            ReactModelPort model, // 接收可替换的模型决策端口。
            AgentToolRegistry tools, // 接收名称稳定的本地工具目录。
            InMemoryTaskRegistry tasks) { // 接收进程内任务生命周期注册表并结束参数列表。
        this.model = model; // 保存模型端口供每轮同步决策使用。
        this.tools = tools; // 保存工具注册表供 Action 执行和 Observation 生成使用。
        this.tasks = tasks; // 保存任务注册表供并发保护和资源释放使用。
    } // 结束手写 Agent 构造方法。

    /**
     * 为一条用户消息创建延迟执行的 ReAct 事件流。
     * 每次订阅都会创建独立上下文和单订阅 Sink，同一 conversationId 则由任务注册表原子拒绝并发运行。
     */
    public Flux<AgentStreamEvent> stream(String conversationId, String message) { // 定义 Agent 运行，但在客户端订阅前不调用模型。
        return Flux.defer(() -> { // 为每个真实 HTTP 订阅隔离全部可变状态。
            ReactRunContext context = new ReactRunContext( // 创建只属于本次请求的 ReAct 上下文。
                    List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(message)), // 按系统规则和用户问题初始化消息历史。
                    MAX_DECISION_ROUNDS); // 应用固定四轮工具决策上限。
            Sinks.Many<AgentStreamEvent> output = Sinks.many().unicast().onBackpressureBuffer(); // 创建单客户端可写事件通道并缓冲短暂消费延迟。
            if (!tasks.register(conversationId, context::markCancelled)) { // 在调用模型前原子占用会话编号，避免重复任务并行。
                return Flux.just( // 为并发冲突创建无需后台工作的有限事件流。
                        AgentStreamEvent.error("conversation is already running"), // 输出与阶段一兼容的会话冲突错误。
                        AgentStreamEvent.complete()); // 输出完成事件让前端退出加载状态。
            } // 结束同会话并发保护分支。

            /*
             * ChatClient.call 是阻塞操作，因此整个 while 循环必须离开 WebFlux 事件线程。
             * 一个运行只使用一个 boundedElastic 工作者，工具调用自然保持模型给出的顺序。
             */
            var worker = Mono.fromRunnable(() -> runLoop(conversationId, context, output)) // 把同步 ReAct 循环包装成可取消的 Reactor 工作单元。
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
            ReactRunContext context, // 接收本次运行独享的消息和状态上下文。
            Sinks.Many<AgentStreamEvent> output) { // 接收向 SSE 下游写入事件的单订阅 Sink。
        try { // 捕获模型边界的意外异常并保证任务能够关闭。
            while (!context.isCancelled() && context.tryStartDecisionRound()) { // 在未取消且未超过四轮时继续请求下一步决策。
                AssistantMessage assistant = model.decide(context.messages(), true); // 携带完整消息历史并允许模型选择工具。
                if (context.isCancelled()) { // 模型阻塞返回时任务可能已被停止，因此结果落库前再次检查。
                    return; // 丢弃取消后迟到的模型结果，不向已终止客户端继续输出。
                } // 结束模型返回后的取消保护分支。
                context.addMessage(assistant); // 把包含文本或 ToolCall 的原始助手消息加入历史。
                if (!assistant.hasToolCalls()) { // 没有 Action 表示模型认为已经可以给出最终答案。
                    finishSuccessfully(conversationId, context, output, assistant.getText()); // 输出完整文本并执行唯一正常收尾。
                    return; // 终止循环，避免最终答案之后再次请求模型。
                } // 结束最终答案判断分支。
                executeToolCalls(context, output, assistant.getToolCalls()); // 串行执行本轮全部 Action 并回填聚合 Observation。
            } // 结束允许工具的模型决策循环。
            if (!context.isCancelled()) { // 正常路径不应静默耗尽轮次，因此先排除主动取消。
                finishWithError(conversationId, context, output, "maximum decision rounds reached"); // 暂以稳定错误结束，后续防护任务会替换为关闭工具的强制总结。
            } // 结束最大轮次临时保护分支。
        } catch (Exception error) { // 捕获模型适配器或循环代码抛出的不可恢复异常。
            finishWithError(conversationId, context, output, errorMessage(error)); // 转换为稳定错误和完成事件，避免 SSE 悬挂。
        } // 结束同步 ReAct 循环异常边界。
    } // 结束 ReAct 主循环方法。

    private void executeToolCalls( // 定义一轮内按顺序执行全部工具调用并追加统一响应消息的过程。
            ReactRunContext context, // 接收需要登记消息历史的本轮上下文。
            Sinks.Many<AgentStreamEvent> output, // 接收工具开始和结束事件的输出通道。
            List<AssistantMessage.ToolCall> toolCalls) { // 接收模型给出的有序工具调用列表。
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(); // 创建与调用顺序一致的 Observation 响应列表。
        for (AssistantMessage.ToolCall toolCall : toolCalls) { // 串行遍历工具调用，避免共享资源并发和响应乱序。
            output.tryEmitNext(AgentStreamEvent.toolStart(toolCall.name(), toolCall.id(), toolCall.arguments())); // 在真实执行前向客户端暴露 Action。
            String observation = tools.execute(toolCall.name(), toolCall.arguments()); // 经过统一错误边界执行本地工具并取得 Observation。
            output.tryEmitNext(AgentStreamEvent.toolEnd(toolCall.name(), toolCall.id(), observation)); // 在执行后输出可关联的工具结束事件。
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), observation)); // 用原调用编号创建模型可理解的工具响应。
        } // 结束本轮有序工具调用循环。
        context.addMessage(ToolResponseMessage.builder().responses(responses).build()); // 把全部 Observation 聚合成紧随 AssistantMessage 的协议消息。
    } // 结束工具调用执行和回填方法。

    private void finishSuccessfully( // 定义最终答案的唯一正常终止序列。
            String conversationId, // 接收需要从任务注册表释放的会话编号。
            ReactRunContext context, // 接收保护单次终止的运行上下文。
            Sinks.Many<AgentStreamEvent> output, // 接收最终文本和完成事件的输出通道。
            String answer) { // 接收模型生成的完整最终回答。
        if (context.tryFinish()) { // 只有第一个终止路径可以输出并关闭协议流。
            tasks.complete(conversationId); // 先释放会话占用，使任务状态与即将结束的流一致。
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
} // 结束手写 ReAct Agent 定义。
