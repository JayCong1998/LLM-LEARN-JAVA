# 阶段 10：工具超时重试与退避

## 目标

阶段 8 已经保证单次同步工具最多等待 3 秒，并把超时转换为模型可理解的 Observation。本阶段解决另一个问题：偶发超时不一定代表工具永久不可用，因此仅对超时进行有限重试。

一次模型工具调用最多实际执行 3 次：首次执行加最多 2 次重试。第 2 次尝试前等待 200 ms，第 3 次尝试前等待 400 ms。

## 两层职责

```text
RetryingToolExecutor
  → 决定是否重试、等待多久、何时通知页面
  → TimedToolExecutor
      → 对每一次真实工具调用施加 3 秒上限
      → AgentToolRegistry
          → 查找并执行具体 ToolCallback
```

`TimedToolExecutor` 仍只负责一次执行的时间边界。`RetryingToolExecutor` 通过包装它实现可靠性策略。职责分开后，单次限时、重试、未来的熔断和限流不会混在 `ManualReactAgent` 的 ReAct 状态机里。

## 为什么只重试超时

重试器只识别精确的 `工具执行超时：<工具名>` Observation。未知工具、参数错误、业务异常和空结果都不重试，直接回填模型。

这是因为超时通常可能来自瞬时网络抖动或短暂负载，而未知工具和参数错误重试也不会改变结果。使用精确相等判断而不是字符串包含判断，可以避免把业务返回的一段普通文本误认为超时。

## SSE 与页面观察

用户对一次模型 ToolCall 仍只看到一张工具卡片：

```text
tool_start
→ tool_retry(attempt=2, delayMillis=200)
→ tool_retry(attempt=3, delayMillis=400)
→ tool_end
```

若第 2 次成功，则不会有第 3 次通知；若第 3 次仍超时，`tool_end` 的最终 Observation 仍是超时文本。`tool_retry` 只含工具名、调用编号、尝试次数和退避时长，不含工具参数、工具结果或模型内部思维链。

浏览器使用 `toolCallId` 找到已有工具卡片，并把状态更新为「第 N 次重试前等待 M ms」。最终 `tool_end` 仍将卡片改为完成状态并展示最终 Observation。

## Observation 回填与取消

中间超时不会直接回填模型。只有最终成功结果或第 3 次最终超时结果，才会同时用于 `tool_end` 和 `ToolResponseMessage`，再由模型决定是否继续使用工具或给出答案。

退避等待发生在既有 `boundedElastic` 工作者。停止请求或浏览器断开会中断它：重试器恢复线程中断标记并停止下一次尝试；Agent 的取消终止路径已经取得 SSE 输出权，因此不会再发送迟到的 `tool_retry`、`tool_end` 或文本，也不会回填 Observation、写会话记忆或成功运行轨迹。

## 本阶段边界

本阶段不重试业务异常，不增加随机抖动、按工具配置、熔断、限流、并行工具调用或底层 HTTP 客户端重试。它建立的是一个小而确定的可靠性边界：单次超时可恢复，取消必须终止，最终结果才进入模型上下文。

## 测试重点

- `RetryingToolExecutorTest` 验证最多 3 次尝试、200 ms/400 ms 通知、非超时不重试与退避中断。
- `AgentStreamEventTest` 验证 `tool_retry` 的安全协议字段。
- `ManualReactAgentTest` 验证页面事件时序和最终 Observation 回填模型。
- `LearningConsoleContractTest` 验证页面按调用编号处理重试事件且不展示内部推理。
