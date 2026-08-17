package com.jaycong.dodo.tool; // 将每次工具执行的安全关联标识放在工具包中。

/**
 * 保存工具保护层判断作用域所需的非敏感执行标识。
 * 该上下文不保存模型参数或 Observation，避免限流与熔断状态意外持有敏感工具数据。
 */
public record ToolExecutionContext( // 使用不可变 record 在 Agent 与工具保护链之间显式传递执行作用域。
        String conversationId, // 保存会话编号以支持按会话隔离的限流规则。
        String toolName, // 保存工具名称以支持每工具独立的熔断状态。
        String toolCallId) { // 保存模型调用编号以支持安全 SSE 重试事件关联。
} // 结束工具执行上下文记录定义。
