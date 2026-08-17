package com.jaycong.dodo.tool; // 将工具重试通知契约放在工具包中以隔离 Agent 的 SSE 传输细节。

@FunctionalInterface // 标记通知可由 Agent 使用 Lambda 接收而不需要知道重试器实现。
public interface ToolRetryListener { // 定义重试计划发生时的最小回调边界。

    void onRetry(int attempt, long delayMillis); // 通知即将开始的实际尝试次数及其前置退避时长。
} // 结束工具重试通知契约。
