package com.jaycong.dodo.agent; // 将事件协议放在 Agent 包中，表明它属于 Agent 对外输出的一部分。

/**
 * Agent 向 HTTP 层输出的稳定事件协议。
 * 记录类型保持事件种类和负载结构统一，使 Agent 不依赖 SSE 等具体传输方式。
 *
 * @param type    事件类型，用于区分文本、错误和完成信号
 * @param content 事件负载，文本和错误事件携带内容，完成事件使用空字符串
 */
public record AgentStreamEvent(String type, String content) { // 使用不可变 record 同时承载事件类型和事件内容。

    public static AgentStreamEvent text(String content) { // 提供文本事件工厂，避免调用方重复手写事件类型字符串。
        return new AgentStreamEvent("text", content); // 把模型产生的一个文本片段包装成 text 事件。
    } // 结束文本事件工厂方法。

    public static AgentStreamEvent error(String content) { // 提供错误事件工厂，统一 Agent 失败和取消时的输出格式。
        return new AgentStreamEvent("error", content); // 把错误说明包装成 error 事件交给下游处理。
    } // 结束错误事件工厂方法。

    public static AgentStreamEvent complete() { // 提供完成事件工厂，用显式事件标记一轮 Agent 输出结束。
        return new AgentStreamEvent("complete", ""); // 完成事件只表达状态，因此使用空字符串作为内容。
    } // 结束完成事件工厂方法。
} // 结束 Agent 流事件协议定义。
