package com.jaycong.dodo.agent; // 将保留旧语义的 call Agent 放在核心 Agent 包中。

import com.jaycong.dodo.memory.ConversationMemory; // 引入跨请求问答记忆边界。
import com.jaycong.dodo.task.InMemoryTaskRegistry; // 引入会话并发和取消注册表。
import com.jaycong.dodo.tool.ToolExecutionPort; // 引入完整工具保护执行链端口。
import com.jaycong.dodo.trace.SuccessfulAgentRunPersistence; // 引入完整成功运行持久化边界。
import org.springframework.stereotype.Service; // 引入生产 call Agent 组件标记。
import reactor.core.publisher.Flux; // 引入 SSE 消费的 Agent 事件流类型。

/** 保留一次性最终文本输出，作为与流式版本对照的稳定实现。 */
@Service // 注册为 Controller 显式注入的旧 call 路径 Agent。
public class ManualReactCallAgent { // 定义只负责暴露 call 语义的薄适配层。

    private final ManualReactAgent delegate; // 保存共享 ReAct 状态机的非 Bean 实例。

    public ManualReactCallAgent(ReactModelPort model, ToolExecutionPort tools, InMemoryTaskRegistry tasks, ConversationMemory memory, SuccessfulAgentRunPersistence runs) { // 接收共享运行边界并组装一次性回答模式。
        this.delegate = new ManualReactAgent(model, tools, tasks, memory, runs); // 不提供最终流端口以维持单个完整 text 事件。
    } // 结束 call Agent 构造方法。

    public Flux<AgentStreamEvent> stream(String conversationId, String message) { // 按既有 SSE 协议启动一次 call 模式 ReAct 运行。
        return delegate.stream(conversationId, message); // 委托共享状态机处理工具、取消、持久化和完成事件。
    } // 结束 call Agent 事件流方法。
} // 结束 call Agent 定义。
