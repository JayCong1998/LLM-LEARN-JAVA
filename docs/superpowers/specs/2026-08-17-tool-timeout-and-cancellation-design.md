# 工具超时与取消传播设计

## 目标

为手写 `ManualReactAgent` 的每次真实工具调用增加固定三秒的执行上限，并把超时转换为模型可理解的 Observation。超时后 ReAct 循环继续：模型可以换用其他工具、解释失败或基于已有信息给出最终回答。

本阶段同时保证用户停止请求或浏览器断开时，正在等待的工具任务会收到中断请求，且迟到的工具结果绝不继续写入 SSE、模型上下文、会话记忆或成功运行轨迹。

## 范围与非目标

范围包括：同步 `ToolCallback` 的限时执行、超时 Observation、取消传播、SSE 生命周期保护，以及相应的单元和 ReAct 集成测试。

本阶段不加入自动重试、退避、熔断、按工具配置不同超时、HTTP 客户端超时配置、工具参数审计、运行轨迹字段扩展或前端改造。固定三秒仅是教学阶段的明确策略，不读取配置文件。

## 架构

```text
ManualReactAgent
  → TimedToolExecutor
    → AgentToolRegistry
      → ToolCallback
```

`ManualReactAgent` 继续拥有 ReAct 循环、串行顺序、`tool_start` / `tool_end` SSE 和 `ToolResponseMessage` 回填职责。新增 `TimedToolExecutor` 只负责把同步工具任务提交到受 Spring 生命周期管理的虚拟线程执行器、等待不超过三秒，并在超时或外层等待被中断时取消该任务。

`AgentToolRegistry` 保持现有职责：按名称查找回调，把未知工具、业务异常和空结果转换为稳定 Observation。它不感知线程、超时或任务生命周期，因此未来可独立替换执行策略。

## 成功、超时与取消流程

真实首次调用的顺序保持不变：

```text
markToolExecution
→ tool_start
→ TimedToolExecutor.execute
→ tool_end
→ ToolResponseMessage
→ 下一轮模型决策
```

正常完成时，执行器返回注册表生成的普通 Observation。超过三秒时，执行器对 `Future` 调用 `cancel(true)`，并返回 `工具执行超时：<工具名>`。Agent 仍发送对应 `tool_end`，再把同一条文本放入 `ToolResponseMessage`；该文本只存在于本次运行上下文，不进入下一次会话记忆。

用户停止和浏览器断开沿用 `InMemoryTaskRegistry.cancel`：它先标记 `ReactRunContext` 已取消，再 dispose 外层 `boundedElastic` 工作订阅。外层线程在执行器等待期间收到中断后，执行器立刻取消内部 `Future`、恢复中断标记并让调用方察觉取消。Agent 在执行器返回边界检查 `context.isCancelled()`，若为真直接退出；取消回调已唯一发送 `error("request cancelled") → complete`，因此不会产生迟到 `tool_end`、工具响应、模型调用或持久化。

## 资源与中断边界

执行器使用 `Executors.newVirtualThreadPerTaskExecutor()`，由 Spring Bean 在销毁时关闭。每一次真实工具调用都提交独立虚拟线程；同轮调用仍由 Agent 外层循环串行等待，因此不会改变模型给出的调用顺序。

`Future.cancel(true)` 只能请求中断，不能强制终止忽略中断的 Java 或第三方代码。这个限制必须在讲义和测试中明确：本阶段保证 Agent 不再等待或消费迟到结果，但不能承诺不合作工具立刻停止。后续接入 HTTP、数据库或进程工具时，适配器本身仍必须设置连接、读取或子进程超时。

## 运行轨迹与持久化边界

超时是可恢复的工具 Observation，而不是运行失败。只要模型随后返回非空最终答案，既有成功运行持久化仍执行一次；轨迹仅保留实际工具名称、首响应耗时、总耗时和 Agent 类型，绝不保存工具参数、Observation 或模型内部思维链。

取消、浏览器断开、空最终回答、模型异常、同会话并发拒绝和运行轨迹写入失败维持现有规则：不写成功会话记忆或成功运行轨迹。

## 测试策略

- `TimedToolExecutorTest`：验证限时完成、超时文本、超时后的中断请求，以及等待线程被中断时取消内部任务。
- `ManualReactAgentTest`：先证明超时后按顺序发出 `tool_start`、超时 `tool_end`，并将 Observation 回填给下一轮模型；同时验证停止等待中工具仍只输出既有取消终止协议。
- 回归：重复工具调用、四轮上限、成功运行轨迹、SSE、会话记忆、并发保护和完整模块测试均保持原语义。

所有实现遵循 TDD：每个行为先编写并运行失败测试，再写最小实现使其变绿。
