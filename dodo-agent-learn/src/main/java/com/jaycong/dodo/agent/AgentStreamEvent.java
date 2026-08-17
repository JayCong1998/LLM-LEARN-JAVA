package com.jaycong.dodo.agent; // 将事件协议放在 Agent 包中，表明它属于 Agent 对外输出的一部分。

/**
 * Agent 向 HTTP 层输出的稳定事件协议。
 * 记录类型保持事件种类和负载结构统一，使 Agent 不依赖 SSE 等具体传输方式。
 *
 * @param type       事件类型，用于区分文本、工具开始、工具结束、错误和完成信号
 * @param content    事件负载，文本、工具结果和错误事件携带内容，其他事件使用空字符串
 * @param toolName   工具事件对应的工具名称，非工具事件使用 null
 * @param toolCallId 模型为单次工具调用分配的编号，用于关联开始和结束事件
 * @param arguments  工具开始事件携带的原始 JSON 参数，其他事件使用 null
 * @param attempt    工具重试事件携带即将开始的实际尝试次数，其他事件使用 null
 * @param delayMillis 工具重试事件携带本次尝试前的退避毫秒数，其他事件使用 null
 */
public record AgentStreamEvent( // 使用不可变 record 统一承载 Agent 各阶段产生的事件数据。
        String type, // 保存稳定事件类型，使 HTTP 层与前端无需理解 Agent 内部实现。
        String content, // 保存最终回答、工具 Observation 或错误说明等主要负载。
        String toolName, // 保存工具名称，使工具生命周期事件具备自描述能力。
        String toolCallId, // 保存工具调用编号，使并发或连续工具卡片能够正确关联。
        String arguments, // 保存工具开始时的原始参数，使页面可以展示模型给出的输入。
        Integer attempt, // 保存工具重试时即将执行的次数，避免把中间超时文本暴露为结果。
        Long delayMillis) { // 保存工具重试前的等待时长，并结束记录组件列表。

    public AgentStreamEvent(String type, String content, String toolName, String toolCallId, String arguments) { // 保留旧五字段构造器，避免已有调用方和测试被协议扩展破坏。
        this(type, content, toolName, toolCallId, arguments, null, null); // 为旧事件明确填充不存在的重试元数据。
    } // 结束五字段兼容构造器。

    public static AgentStreamEvent text(String content) { // 提供文本事件工厂，避免调用方重复手写事件类型字符串。
        return new AgentStreamEvent("text", content, null, null, null, null, null); // 把模型产生的文本包装成不含工具元数据的 text 事件。
    } // 结束文本事件工厂方法。

    public static AgentStreamEvent error(String content) { // 提供错误事件工厂，统一 Agent 失败和取消时的输出格式。
        return new AgentStreamEvent("error", content, null, null, null, null, null); // 把错误说明包装成不含工具元数据的 error 事件。
    } // 结束错误事件工厂方法。

    public static AgentStreamEvent complete() { // 提供完成事件工厂，用显式事件标记一轮 Agent 输出结束。
        return new AgentStreamEvent("complete", "", null, null, null, null, null); // 完成事件只表达状态，因此其内容和工具元数据均为空。
    } // 结束完成事件工厂方法。

    public static AgentStreamEvent toolStart( // 提供工具开始事件工厂，统一模型决定调用工具时的可观察协议。
            String toolName, // 接收模型选择的工具名称。
            String toolCallId, // 接收模型生成的工具调用编号。
            String arguments) { // 接收模型生成的原始 JSON 参数，并结束参数列表。
        return new AgentStreamEvent("tool_start", "", toolName, toolCallId, arguments, null, null); // 创建尚无 Observation 的工具开始事件。
    } // 结束工具开始事件工厂方法。

    public static AgentStreamEvent toolEnd( // 提供工具结束事件工厂，使客户端可以更新对应的运行中工具卡片。
            String toolName, // 接收本次已经执行结束的工具名称。
            String toolCallId, // 接收与工具开始事件相同的调用编号。
            String content) { // 接收工具执行得到的 Observation，并结束参数列表。
        return new AgentStreamEvent("tool_end", content, toolName, toolCallId, null, null, null); // 创建携带 Observation 且不重复参数的结束事件。
    } // 结束工具结束事件工厂方法。

    public static AgentStreamEvent toolRetry( // 提供工具重试事件工厂，使页面可观察确定的重试计划。
            String toolName, // 接收即将再次执行的工具名称。
            String toolCallId, // 接收与开始事件一致的模型调用编号。
            int attempt, // 接收即将开始的实际尝试次数。
            long delayMillis) { // 接收本次尝试前需要等待的退避时长，并结束参数列表。
        return new AgentStreamEvent("tool_retry", "", toolName, toolCallId, null, attempt, delayMillis); // 创建不携带参数和 Observation 的安全重试事件。
    } // 结束工具重试事件工厂方法。
} // 结束 Agent 流事件协议定义。
