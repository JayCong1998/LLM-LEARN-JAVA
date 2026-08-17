# 工具超时重试与退避设计

## 1. 目标

在阶段 8 的单次工具超时保护之上，为偶发超时提供受控重试能力。一次模型工具调用最多执行 3 次：首次执行失败后最多额外重试 2 次。只有 `TimedToolExecutor` 返回的稳定超时 Observation 才能触发重试。

本阶段让浏览器可观察重试过程，同时保持既有 ReAct、SSE、取消、会话记忆和成功运行轨迹的边界。

## 2. 范围与非目标

### 2.1 本阶段包含

- 新增可组合的 `RetryingToolExecutor`，实现现有 `ToolExecutionPort`。
- 第 2 次尝试前等待 200 ms，第 3 次尝试前等待 400 ms。
- 新增 `tool_retry` SSE 事件，暴露安全的工具名、工具调用编号、即将执行的次数和等待时长。
- 保留原有一次 `tool_start` 与一次最终 `tool_end` 事件。
- 在等待退避或单次执行期间正确传播取消。

### 2.2 本阶段不包含

- 重试未知工具、参数错误、业务异常、空结果或模型调用。
- 按工具、租户或配置文件定制重试次数和退避时长。
- 随机抖动（jitter）、熔断、限流、并行工具调用或 HTTP 客户端重试。
- 保存工具参数、工具 Observation 或模型内部思维链。

## 3. 架构与职责

```text
ManualReactAgent（ReAct、SSE 生命周期）
  → RetryingToolExecutor（超时识别、退避、重试通知）
    → TimedToolExecutor（单次 3 秒上限与中断传播）
      → AgentToolRegistry（工具查找与业务异常转换）
```

`TimedToolExecutor` 的职责不变：执行一次工具并把超时转换为 `工具执行超时：<工具名>`。`RetryingToolExecutor` 只识别该稳定结果，不解析其他业务错误文本。`ManualReactAgent` 仍拥有 SSE 输出权，因此重试器通过小型通知回调报告重试计划，而不直接依赖 Reactor 或 `Sinks.Many`。

## 4. 执行与事件时序

每个模型 `ToolCall` 只向用户表现为一个工具生命周期：

```text
tool_start
→ 第 1 次 TimedToolExecutor.execute
→ tool_retry(attempt=2, delayMillis=200)
→ 等待 200 ms
→ 第 2 次 TimedToolExecutor.execute
→ tool_retry(attempt=3, delayMillis=400)
→ 等待 400 ms
→ 第 3 次 TimedToolExecutor.execute
→ tool_end（最终成功结果或最终超时结果）
```

若第 1 或第 2 次尝试成功，后续尝试和 `tool_retry` 均不发生。第 3 次仍超时则不再重试，原样返回最终超时 Observation。Agent 照旧发送 `tool_end`，将相同文本组成 `ToolResponseMessage` 回填模型；超时仍是模型可恢复的 Observation，不是整次运行的错误。

新增 `tool_retry` 事件字段如下：

| 字段 | 含义 |
| --- | --- |
| `type` | 固定为 `tool_retry`。 |
| `toolName` | 即将再次执行的工具名。 |
| `toolCallId` | 与模型 `ToolCall` 对应的调用编号。 |
| `attempt` | 即将开始的实际尝试次数，仅可能为 `2` 或 `3`。 |
| `delayMillis` | 本次尝试前的退避时长，仅可能为 `200` 或 `400`。 |

不在事件、日志、运行轨迹或数据库中输出工具参数、Observation、隐藏推理或其他敏感内容。

## 5. 取消、异常与持久化边界

退避等待必须响应外层 `boundedElastic` 工作线程的中断。停止请求或浏览器断开先由既有 `InMemoryTaskRegistry` 标记 `ReactRunContext` 已取消并 dispose 工作者；如果重试器在等待，必须恢复中断标记并退出，而不能开始下一次工具执行。

取消已取得 SSE 终止权时，后续不得发送迟到的 `tool_retry`、`tool_end` 或 `text`，不得回填 `ToolResponseMessage`，也不得追加会话记忆或成功运行轨迹。已经发出的 `tool_start` 可以保留，因为它对应真实开始的工具 Action。

非超时 Observation 不重试，保持 `AgentToolRegistry` 已有的返回语义。最终回答成功时，阶段 6 的持久化仍只保存 question、answer、安全工具名称与性能指标；重试次数不新增落库字段。

## 6. 测试策略

采用 TDD：先创建失败测试并实际运行确认红灯，再写最小实现。

- `RetryingToolExecutorTest`：验证首次成功不重试、前两次超时后的重试次数与 200 ms/400 ms 通知、第二或第三次成功即停止、第三次超时作为最终结果、非超时结果不重试，以及退避等待被中断时不执行后续尝试。
- `ManualReactAgentTest`：验证 `tool_start → tool_retry → tool_end` 的顺序，最终成功或超时 Observation 与模型回填一致，取消期间没有迟到事件或后续模型调用。
- `AgentStreamEventTest` 与前端契约测试：验证 `tool_retry` 的事件字段、序列化与浏览器解析。

完成前运行 `mvn -pl dodo-agent-learn test`、`git diff --check` 与 `git status --short`。
