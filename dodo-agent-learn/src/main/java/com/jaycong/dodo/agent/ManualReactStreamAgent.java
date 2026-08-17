package com.jaycong.dodo.agent; // 将最终回答流式输出 Agent 放在核心 Agent 包中。

import com.jaycong.dodo.memory.ConversationMemory; // 引入跨请求问答记忆边界。
import com.jaycong.dodo.task.InMemoryTaskRegistry; // 引入会话并发和取消注册表。
import com.jaycong.dodo.tool.AgentToolRegistry; // 引入兼容测试组装所需的工具注册表。
import com.jaycong.dodo.tool.ToolExecutionPort; // 引入完整工具保护执行链端口。
import com.jaycong.dodo.trace.SuccessfulAgentRunPersistence; // 引入完整成功运行持久化边界。
import org.springframework.stereotype.Service; // 引入生产 stream Agent 组件标记。
import org.springframework.beans.factory.annotation.Autowired; // 引入显式指定生产构造器的依赖注入标记。
import reactor.core.publisher.Flux; // 引入 SSE 消费的 Agent 事件流类型。

/** 保留 stream 路由入口，但使用 ReAct 决策直接返回最终答案，不再二次请求模型伪造分片。 */
@Service // 注册为页面默认使用的 stream 路径 Agent。
public class ManualReactStreamAgent { // 定义只负责选择流式最终回答模式的薄适配层。

    private final ManualReactAgent delegate; // 保存共享 ReAct 状态机的非 Bean 实例。

    @Autowired // 在测试兼容构造器存在时明确选择带保护执行端口的生产构造器。
    public ManualReactStreamAgent(ReactModelPort model, ToolExecutionPort tools, InMemoryTaskRegistry tasks, ConversationMemory memory, SuccessfulAgentRunPersistence runs) { // 接收共享运行边界。
        this.delegate = new ManualReactAgent(model, tools, tasks, memory, runs); // 直接采用模型决策的最终文本，禁止额外模型请求。
    } // 结束生产 stream Agent 构造方法。

    public ManualReactStreamAgent(ReactModelPort model, AgentToolRegistry tools, InMemoryTaskRegistry tasks, ConversationMemory memory, SuccessfulAgentRunPersistence runs) { // 提供直连注册表的测试组装入口。
        this.delegate = new ManualReactAgent(model, tools::execute, tasks, memory, runs); // 适配注册表并保留单次最终文本语义。
    } // 结束测试 stream Agent 构造方法。

    public Flux<AgentStreamEvent> stream(String conversationId, String message) { // 按 SSE 协议启动一次最终回答流式 ReAct 运行。
        return delegate.stream(conversationId, message); // 委托共享状态机处理工具、取消、持久化和完成事件。
    } // 结束 stream Agent 事件流方法。
} // 结束 stream Agent 定义。
