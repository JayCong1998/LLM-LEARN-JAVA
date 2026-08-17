package com.jaycong.dodo.tool; // 将工具执行抽象放在工具包中，隔离 Agent 编排与线程策略。

public interface ToolExecutionPort { // 定义 Agent 执行已选择工具所依赖的窄端口。

    String execute(String toolName, String arguments); // 根据模型给出的名称和原始参数返回可回填的 Observation。

    default String execute(String toolName, String arguments, ToolRetryListener retryListener) { // 为不具备重试能力的既有端口提供向后兼容的通知重载。
        return execute(toolName, arguments); // 忽略通知并委托原有单次执行，保持 Lambda 仍只有一个抽象方法。
    } // 结束兼容重试通知执行方法。
} // 结束工具执行端口定义。
