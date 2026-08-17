# 工具保护与最终回答流式输出设计

## 1. 目标

本联合阶段包含两个相邻主题：

- 阶段 11：为每个工具增加独立的熔断（Circuit Breaker）与会话级限流。
- 阶段 12：保留一次性最终回答的 `call` 对照实现，新增 Token 级最终回答的 `stream` 实现，并让页面只使用流式接口。

两项能力共同复用现有 ReAct、工具超时重试、Token 预算、取消、会话记忆和成功运行轨迹边界。

## 2. 双接口与双 Agent

旧的完整最终回答逻辑迁移到 `ManualReactCallAgent`，通过 `/api/agent/chat/call` 暴露。它在工具循环结束后使用现有同步模型调用，并只输出一个完整 `text` SSE 事件。

新增 `ManualReactStreamAgent`，通过 `/api/agent/chat/stream` 暴露。它与 call Agent 使用同样的同步 `ReactModelPort.decide(...)` 完成系统提示、上下文预算、工具选择、工具 Observation 回填和强制收尾判断；只有最终不再调用工具的回答改由 `FinalAnswerStreamPort` 流式产生多个文本片段。

两个 Agent 通过共享的运行协调器复用初始化、工具循环、任务注册、取消检查、完成闸门、成功持久化和错误转换，不能复制两套容易漂移的 ReAct 状态机。页面只请求 `/api/agent/chat/stream`；call 路由、Agent、测试与教程保留，作为学习对照，不新增模式参数或页面开关。

## 3. 最终回答流式协议

`FinalAnswerStreamPort` 隔离 Spring AI 的 `ChatClient.stream()`。流式 Agent 在最终回答阶段把预算内消息快照交给该端口；每个非空片段立即转换为一个 `AgentStreamEvent.text`。

正常完成时，Agent 聚合已发送的片段作为完整回答：

```text
tool_start / tool_retry / tool_end（零次或多次）
→ text（零次或多次）
→ 成功持久化 question、answer、运行轨迹
→ complete
```

空流或仅空白片段视为 `模型未返回最终答案`，不写记忆或轨迹。流式模型异常在已输出部分文本后发送 `error(<稳定错误>) → complete`，不写成功数据。取消时 dispose 流订阅并保持既有 `request cancelled → complete` 协议，不再输出片段、错误重复序列、记忆或轨迹。

由于流式文本已到客户端，持久化无法在首个片段前原子完成。若流正常结束后写入失败，已发片段保留，并发送 `error("运行轨迹保存失败：…") → complete`；不创建成功问答或成功运行轨迹。这是透明地保留流式体验与持久化事实的取舍。

## 4. 工具保护链

每次真实工具执行通过固定组合：

```text
共享 ReAct 协调器
→ CircuitBreakingToolExecutor
→ RateLimitedToolExecutor
→ RetryingToolExecutor
→ TimedToolExecutor
→ AgentToolRegistry
```

`CircuitBreakingToolExecutor` 按工具名维护进程内独立状态，使用单调时钟判断 30 秒窗口：

- 关闭状态下，最终 `工具执行超时：<工具名>` 或 `工具执行失败：...` 连续 3 次，将该工具打开熔断 30 秒。
- 打开期间返回 `工具已熔断：<工具名>`，不消耗限流额度、不进入重试或真实工具执行。
- 到期后进入半开状态，只允许 1 次真实调用；成功清零并关闭，失败重新打开 30 秒。
- 未知工具、参数错误、限流拒绝、取消和重复工具调用跳过都不增加连续失败计数。

`RateLimitedToolExecutor` 按 `conversationId + toolName` 维护进程内滑动一分钟窗口。窗口内最多允许 10 次实际工具请求；第 11 次及以后返回 `工具调用过于频繁：<工具名>`，不进入重试，也不改变熔断连续失败数。会话标识必须作为执行上下文传入保护层，不能通过线程本地变量或全局可变当前会话传递。

熔断与限流均是教学阶段内存状态：应用重启后清空，不写数据库、不暴露管理接口、不记录工具参数。

## 5. 兼容性与安全边界

- 原有 `/api/agent/chat/stream` 在本阶段切换为新流式 Agent；前端只改目标地址，不改变停止、会话记忆、运行轨迹面板和安全 DOM 渲染方式。
- 旧一次性 Agent 改由 `/api/agent/chat/call` 使用；保留其单个 `text` 行为，便于与新流式版本对比。
- 工具重试事件仍只有工具名、调用编号、尝试次数和退避时长。
- 熔断、限流、工具 Observation、模型片段和运行上下文不会进入下一请求的 `ConversationMemory`；记忆仍只保存最终成功的 question 与 answer。
- 不保存、返回或展示模型内部思维链；`thinking` 字段仍不使用。

## 6. 测试策略

严格 TDD：每一项先写失败测试并实际确认红灯，再写最小实现。

- 工具保护单元测试：每工具状态隔离、3 次最终失败、30 秒熔断、半开单探测、成功复位、限流窗口、会话隔离、熔断与限流拒绝不计失败。
- Agent 测试：call 保留单文本；stream 工具后多文本片段、聚合持久化、空流、模型流异常、取消和持久化失败。
- 适配器测试：最终流消息、工具禁用、预算快照与 Spring AI `ChatClient.stream()` 映射。
- Web 与页面契约：`/chat/call` 和 `/chat/stream` 分别路由到正确 Agent，页面只包含 stream 地址，且无内部推理文案。

完成前运行 `mvn -pl dodo-agent-learn test`、`git diff --check` 和 `git status --short`。
