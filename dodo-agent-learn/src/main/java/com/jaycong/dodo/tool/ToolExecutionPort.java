package com.jaycong.dodo.tool; // 将工具执行抽象放在工具包中，隔离 Agent 编排与线程策略。

public interface ToolExecutionPort { // 定义 Agent 执行已选择工具所依赖的窄端口。

    String execute(String toolName, String arguments); // 根据模型给出的名称和原始参数返回可回填的 Observation。
} // 结束工具执行端口定义。
