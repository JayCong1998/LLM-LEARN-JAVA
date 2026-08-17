# 阶段 8：工具超时与取消传播

## 目标

给每次真实工具调用加上三秒执行上限，同时保持 ReAct 的核心语义：工具超时不是整个 Agent 失败，而是一条模型可理解的 Observation。模型收到它后可以换用工具、说明限制，或基于已有信息完成回答。

## 为什么不能只在 Agent 线程里直接调用工具

`ManualReactAgent` 的整个 ReAct 循环运行在 `boundedElastic` 线程：其中包含阻塞模型调用、消息组装和按顺序的工具 Action。若直接在这条线程无期限调用同步工具，慢速 I/O 会让一次运行永远无法进入下一轮模型决策，也不能得到明确的超时结果。

本阶段将工具执行拆出为独立边界：

```text
ManualReactAgent（boundedElastic，负责状态机）
  → TimedToolExecutor（等待最多 3 秒）
    → 虚拟线程中的 AgentToolRegistry.execute
      → ToolCallback
```

`AgentToolRegistry` 仍只负责名称查找、业务异常和空结果到 Observation 的转换。`TimedToolExecutor` 则只负责线程、限时、超时和中断传播。这样以后要加入重试、限流或按工具配置超时，不需要把线程细节混入 ReAct 循环。

## 超时如何成为 Observation

工具开始执行时，Agent 先发送原有 `tool_start`。执行器提交内部任务，最多等待三秒：

- 正常完成：返回工具的普通 Observation。
- 超时：调用 `Future.cancel(true)`，返回 `工具执行超时：<工具名>`。
- 工具自身异常：仍由注册表转换成 `工具执行失败：...`。

没有取消时，Agent 将 Observation 依次用于：

```text
tool_end SSE
→ ToolResponseMessage
→ 下一轮模型决策
```

因此浏览器看到的 `tool_end` 与模型收到的工具响应一定是同一条超时文本。超时后的最终回答若非空，仍按阶段 6 的规则一次性保存问答和安全运行轨迹；其中只保存工具名称和耗时，不保存参数、Observation 或内部思维链。

## 停止请求如何传递

用户点击停止或浏览器断开时，`InMemoryTaskRegistry.cancel` 会先标记 `ReactRunContext` 已取消，再 dispose 外层 `boundedElastic` 工作订阅。

若 Agent 正在等待 `TimedToolExecutor`，外层线程收到 `InterruptedException`。执行器会取消内部 `Future`、恢复中断标记并抛出取消异常。取消回调已经取得唯一终止权，先输出：

```text
error("request cancelled")
→ complete
```

已在取消前发出的 `tool_start` 会保留，因为它是真实发生过的 Action；但 Agent 在工具等待返回边界检查取消标记，所以绝不发送迟到的 `tool_end`、不回填 `ToolResponseMessage`、不再调用模型，也不写会话记忆或成功运行轨迹。

## 协作式中断的限制

`Future.cancel(true)` 只能请求线程中断，不能强制杀死忽略中断的 Java 代码或第三方 SDK。这个设计保证 Agent 不再等待和消费迟到结果，却不能保证不合作工具瞬间停止。

因此未来的 HTTP、数据库和子进程工具还必须在各自适配器中配置连接超时、读取超时或进程超时。Agent 级三秒上限是最后一道运行边界，不应取代底层资源控制。

## 测试重点

- `TimedToolExecutorTest` 验证正常结果、超时 Observation、超时中断以及外层取消到内部任务的中断传播。
- `ManualReactAgentTest` 验证超时 Observation 同时出现在 `tool_end` 与下一轮模型上下文中。
- 取消测试验证已发送的 `tool_start` 后只会有 `request cancelled → complete`，没有迟到 `tool_end` 或 `text`。

这使“超时可恢复、取消必须终止”的边界既可观察，又能在后续阶段持续回归验证。
