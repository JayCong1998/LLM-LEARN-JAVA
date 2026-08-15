package com.jaycong.dodo.agent; // 将核心编排服务放在 Agent 包中，集中表达一轮任务的生命周期规则。

import com.jaycong.dodo.task.InMemoryTaskRegistry; // 引入任务注册表，用于会话互斥、订阅绑定和主动取消。
import org.springframework.stereotype.Service; // 引入服务注解，把 Agent 编排器注册为 Spring 业务 Bean。
import reactor.core.publisher.Flux; // 引入 Reactor 多值流，向 HTTP 层持续输出多个 Agent 事件。
import reactor.core.publisher.SignalType; // 引入终止信号枚举，用于区分客户端取消和其他结束方式。
import reactor.core.publisher.Sinks; // 引入可编程事件汇，用于把模型回调转换为下游可订阅的 Flux。

import java.util.concurrent.atomic.AtomicBoolean; // 引入原子布尔值，协调成功、异常和取消之间的终止竞争。

/**
 * 编排一次最小流式 Agent 对话的核心服务。
 * 该服务负责会话互斥、模型订阅、事件转换、主动取消和所有终止路径的资源清理。
 */
@Service // 把 Agent 注册为单例服务，供 Web Controller 通过构造器注入使用。
public class StreamingChatAgent { // 定义模型文本流到稳定 Agent 事件流之间的生命周期编排器。

    private final ChatStreamPort model; // 保存抽象模型端口，使核心逻辑不依赖 Spring AI 的具体类型。
    private final InMemoryTaskRegistry tasks; // 保存任务注册表，使对话可以被查询、互斥和主动停止。

    public StreamingChatAgent(ChatStreamPort model, InMemoryTaskRegistry tasks) { // 通过构造器显式注入模型边界和任务管理依赖。
        this.model = model; // 保存模型流端口，后续用它发起实际文本生成。
        this.tasks = tasks; // 保存共享任务注册表，后续用它管理当前运行会话。
    } // 结束 Agent 构造方法。

    /**
     * 为一轮对话创建可取消的模型文本流。
     * defer 保证每个 HTTP 订阅都拥有独立的 Sink、终止标记和任务注册过程。
     *
     * @param conversationId 会话唯一编号，同时作为并发互斥和停止任务的索引
     * @param message        本轮发送给模型的用户消息
     * @return 延迟执行并持续产生文本、错误和完成事件的 Agent 流
     */
    public Flux<AgentStreamEvent> stream(String conversationId, String message) { // 定义一轮对话，但在下游订阅前不创建任务或调用模型。
        return Flux.defer(() -> { // 把全部可变状态放进 defer，使每次订阅都获得隔离的运行环境。
            Sinks.Many<AgentStreamEvent> output = Sinks.many() // 创建可由模型回调主动写入多个事件的 Sink 构建器。
                    .unicast() // 限制输出流只有一个订阅者，符合一个 HTTP 请求对应一个客户端的模型。
                    .onBackpressureBuffer(); // 当模型生产快于网络消费时临时缓冲事件，避免直接丢失文本片段。
            AtomicBoolean finished = new AtomicBoolean(); // 初始为 false，并作为所有终止路径共享的单次完成闸门。

            /*
             * 主动取消既要停止上游模型订阅，也要让仍连接的客户端收到明确终止协议。
             * TaskRegistry 负责 dispose 上游，然后调用此回调发送 error、complete 并关闭 Reactor 流。
             */
            Runnable onCancel = () -> { // 创建任务注册表在主动取消成功后执行的下游通知回调。
                if (finished.compareAndSet(false, true)) { // 仅允许最先到达的终止路径把状态从运行中改为已结束。
                    output.tryEmitNext(AgentStreamEvent.error("request cancelled")); // 先发送取消原因，使客户端知道任务并非正常完成。
                    output.tryEmitNext(AgentStreamEvent.complete()); // 再发送协议级完成事件，使客户端统一结束加载状态。
                    output.tryEmitComplete(); // 最后关闭 Reactor 流，释放下游订阅和 SSE 连接。
                } // 结束单次终止保护分支；失败说明其他路径已经完成收尾。
            }; // 结束主动取消回调定义。

            /*
             * 必须先注册会话，再订阅模型，才能用 putIfAbsent 阻止同一会话重复运行。
             * 注册失败时没有创建模型订阅，因此直接返回有限事件流，不需要任务清理。
             */
            if (!tasks.register(conversationId, onCancel)) { // 原子注册失败表示相同会话编号已经有任务运行。
                return Flux.just( // 创建只包含错误和完成信号的有限流，避免发起第二个模型请求。
                        AgentStreamEvent.error("conversation is already running"), // 告诉客户端重复会话被并发保护拒绝。
                        AgentStreamEvent.complete()); // 发送完成事件，让客户端正常结束本次失败请求的 UI 状态。
            } // 结束重复会话保护分支。

            /*
             * 注册成功后才能订阅模型；subscribe 会立即返回用于取消上游的 Disposable。
             * Disposable 随后绑定到任务条目，使停止接口能够真正终止模型请求，而不只是关闭前端显示。
             */
            var subscription = model.stream(message).subscribe( // 调用抽象模型端口并立即订阅，使延迟的模型请求开始执行。
                    chunk -> { // 为模型产生的每一个文本片段注册 next 回调。
                        if (!finished.get()) { // 终止之后忽略竞争中迟到的模型片段，避免向已关闭协议追加文本。
                            output.tryEmitNext(AgentStreamEvent.text(chunk)); // 把原始文本片段转换为稳定 text 事件并写入输出 Sink。
                        } // 结束运行状态检查分支。
                    }, // 结束模型文本片段回调，并进入异常回调参数。
                    error -> finishWithError(conversationId, output, finished, error), // 模型流失败时统一发送错误和完成事件。
                    () -> finishSuccessfully(conversationId, output, finished)); // 模型流正常结束时统一发送完成事件并关闭输出。
            tasks.attach(conversationId, subscription); // 把模型 Disposable 绑定到任务；若取消已先发生，注册表会立即释放它。

            /*
             * asFlux 暴露只读输出流；doFinally 无论正常完成、异常还是客户端断开都会执行。
             * 客户端取消需要向上游传播 dispose，其他终止方式只需执行幂等的注册表清理。
             */
            return output.asFlux().doFinally(signal -> { // 将可写 Sink 转换为只读 Flux，并注册最终清理钩子。
                if (signal == SignalType.CANCEL) { // CANCEL 表示下游主动断开，模型可能仍在生成，因此必须执行取消。
                    tasks.cancel(conversationId); // 从注册表移除任务、dispose 模型订阅，并触发取消回调的终止保护。
                } else { // ON_COMPLETE 或 ON_ERROR 表示输出流自身已经结束，不应再执行取消语义。
                    tasks.complete(conversationId); // 幂等移除任务并标记关闭，不重复发送取消事件。
                } // 结束最终信号类型分支。
            }); // 结束输出 Flux 的最终清理钩子并返回给 HTTP 层。
        }); // 结束 defer 工厂；真正逻辑会在每次下游订阅时独立执行。
    } // 结束一轮流式对话的创建方法。

    /**
     * 处理模型流的正常完成信号。
     * 原子终止闸门保证它不会与取消或异常路径重复发送完成事件。
     */
    private void finishSuccessfully( // 定义只在当前类内部使用的正常完成收尾方法。
            String conversationId, // 接收需要从任务注册表清理的会话编号。
            Sinks.Many<AgentStreamEvent> output, // 接收本轮对话专属的事件输出 Sink。
            AtomicBoolean finished) { // 接收本轮对话专属的原子终止状态并结束参数列表。
        if (finished.compareAndSet(false, true)) { // 只有第一个终止者能够进入并执行一次完整收尾。
            tasks.complete(conversationId); // 先移除运行任务，使会话可以安全发起下一轮请求。
            output.tryEmitNext(AgentStreamEvent.complete()); // 向客户端发送协议级完成事件。
            output.tryEmitComplete(); // 关闭 Reactor 输出流，并触发下游最终清理钩子。
        } // 结束单次正常完成保护分支。
    } // 结束正常完成收尾方法。

    /**
     * 处理模型流的异常终止信号。
     * 错误会被转换为业务事件，随后仍发送完成事件并关闭流，让前端使用统一终止流程。
     */
    private void finishWithError( // 定义只在当前类内部使用的异常完成收尾方法。
            String conversationId, // 接收需要从任务注册表清理的会话编号。
            Sinks.Many<AgentStreamEvent> output, // 接收本轮对话专属的事件输出 Sink。
            AtomicBoolean finished, // 接收本轮对话专属的原子终止状态。
            Throwable error) { // 接收模型流抛出的异常，并结束方法参数列表。
        if (finished.compareAndSet(false, true)) { // 只有异常率先赢得终止竞争时才发送错误和完成事件。
            tasks.complete(conversationId); // 先清理运行任务，但不执行主动取消回调。
            output.tryEmitNext(AgentStreamEvent.error(error.getMessage())); // 把异常消息转换为稳定 error 事件交给客户端。
            output.tryEmitNext(AgentStreamEvent.complete()); // 在错误事件之后发送协议级完成信号。
            output.tryEmitComplete(); // 最后关闭 Reactor 流，确保 SSE 连接能够释放。
        } // 结束单次异常完成保护分支。
    } // 结束异常完成收尾方法。
} // 结束流式 Agent 编排服务定义。
