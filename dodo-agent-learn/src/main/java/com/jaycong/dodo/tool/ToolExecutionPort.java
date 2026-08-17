package com.jaycong.dodo.tool; // 将工具执行抽象放在工具包中，隔离 Agent 编排与线程策略。

public interface ToolExecutionPort { // 定义 Agent 执行已选择工具所依赖的窄端口。

    String execute(String toolName, String arguments); // 根据模型给出的名称和原始参数返回可回填的 Observation。

    default String execute(String toolName, String arguments, ToolRetryListener retryListener) { // 为不具备重试能力的既有端口提供向后兼容的通知重载。
        return execute(toolName, arguments); // 忽略通知并委托原有单次执行，保持 Lambda 仍只有一个抽象方法。
    } // 结束兼容重试通知执行方法。

    default String execute(ToolExecutionContext context, String arguments) { // 为保护链提供携带会话与调用标识的兼容执行入口。
        return execute(context.toolName(), arguments); // 默认端口只需要工具名，因此安全丢弃尚未使用的额外标识。
    } // 结束上下文兼容执行方法。

    default String execute(ToolExecutionContext context, String arguments, ToolRetryListener retryListener) { // 为重试与保护链提供同时携带上下文和通知的统一入口。
        return execute(context.toolName(), arguments, retryListener); // 委托既有重试重载，保持旧端口实现无需修改。
    } // 结束上下文重试兼容执行方法。
} // 结束工具执行端口定义。
