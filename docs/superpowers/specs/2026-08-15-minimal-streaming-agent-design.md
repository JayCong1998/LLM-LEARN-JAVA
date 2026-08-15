# 最小流式 Agent：第一阶段设计

## 学习目标

通过一个可运行、可测试的最小系统，理解 Agent 的基础运行边界：HTTP 流式协议、模型输出流、会话标识、任务取消，以及外部模型依赖的隔离。此阶段不实现工具调用或 ReAct 循环；下一阶段将在此基础上手写模型决策、工具执行和多轮反馈。

## 范围

### 包含

- 浏览器提交 `conversationId` 与问题，并接收 SSE 流。
- 后端持续输出统一 JSON 事件：`text`、`error`、`complete`。
- 一个单轮 `StreamingChatAgent`，将模型文本流转换为上述事件。
- `InMemoryTaskRegistry`：按会话登记运行中的订阅，支持取消和结束后的资源清理。
- `ChatStreamPort` 接口及 Spring AI 适配器；测试中以确定性的假实现替换真实模型。
- 最小前端页面和后端自动化测试。

### 明确不包含

- 工具定义、工具选择、ReAct 多轮循环。
- 会话历史、MySQL、Redis、RAG、MCP、联网搜索、文件和 PPT。
- 真实模型调用的自动化测试。

## 架构与职责

`ChatController` 仅处理 HTTP 参数、SSE 响应和取消接口；它不持有模型逻辑。`StreamingChatAgent` 负责发起模型流、将文本转换为事件并将流的订阅交给任务表管理。`ChatStreamPort` 是模型边界，生产适配器使用 Spring AI，测试替身返回预设的 `Flux<String>`。`InMemoryTaskRegistry` 只管理同一进程内的运行任务；第三阶段才演进到参考项目的 Redis 协调模型。

请求链路为：浏览器 → `ChatController` → `StreamingChatAgent` → `ChatStreamPort`；取消链路为：浏览器 → `ChatController` → `InMemoryTaskRegistry` → Reactor `Disposable`。

## HTTP 与事件协议

- `GET /api/agent/chat/stream?conversationId=<id>&message=<text>`：返回 `text/event-stream`。
- `POST /api/agent/tasks/{conversationId}/stop`：取消当前会话的运行任务；无任务时返回可读状态。
- 每一条 SSE data 都是 JSON：`{"type":"text","content":"..."}`、`{"type":"error","content":"..."}` 或 `{"type":"complete","content":""}`。

`conversationId` 在第一阶段只是运行任务的键。它不代表已实现的持久化记忆，避免把“请求标识”和“对话记忆”混为一谈。

## 错误、取消与资源边界

- 空会话标识或空问题在 Controller 层拒绝。
- 同一会话同时运行第二个请求时拒绝，避免一个停止操作误取消另一条流。
- 模型流异常时发送一个 `error` 事件并清理任务。
- 客户端断开、模型完成、模型失败或显式停止都会清理任务表。
- 第一阶段不向浏览器发送模型内部思考过程；后续工具阶段再以受控事件展示执行状态。

## 测试与验收

- Agent 测试：假模型产生分段文本，断言文本事件顺序和完成事件。
- 任务表测试：任务可登记、取消、清理，且不能重复登记同一个会话。
- 接口测试：验证 SSE content type、非法参数和停止接口。
- 手工验收：启动应用后，在页面发送一个问题；确认逐字/逐段显示；发送过程中点击停止，确认无后续文本且页面显示已停止。

## 与参考项目的对照

本阶段聚焦参考项目的 `AgentController`、`BaseAgent` 和 `AgentTaskManager`。学习版会保留其流事件、会话键与任务取消这三项设计意图，但不复制多 Agent 分发、Redis、持久化与推荐问题逻辑。通过较小的接口，先理解每一个生命周期节点，再逐步增加复杂度。
