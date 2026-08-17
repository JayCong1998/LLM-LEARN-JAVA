# Agent 运行轨迹持久化设计

## 目标

在不改变既有 SSE、取消、同会话并发保护和 `boundedElastic` 线程边界的前提下，将每次**成功**的 `ManualReactAgent` 运行以一条完整记录写入 `dodo.ai_session`。

记录用于学习观察、页面后续展示和排障，包含实际执行工具、首个可观察事件耗时、总响应耗时与 Agent 类型。跨请求 `ConversationMemory` 仍然只向模型回放 `question` 和 `answer`，绝不回放工具轨迹或任何内部推理内容。

## 范围与非目标

本阶段写入以下 `ai_session` 字段：

| 字段 | 来源与语义 |
| --- | --- |
| `session_id` | HTTP 请求的 `conversationId`。 |
| `question` | 本次用户问题。 |
| `answer` | 已验证为非空的最终回答。 |
| `tools` | 实际进入工具注册表执行路径的工具名，按首次执行顺序去重后的安全结构化 JSON 数组。 |
| `first_response_time` | 从 `boundedElastic` 工作线程开始，到首次发送 `tool_start` 或最终 `text` 前的毫秒数。 |
| `total_response_time` | 从工作线程开始，到最终回答就绪、准备持久化前的毫秒数；不包含本次数据库写入。 |
| `agent_type` | 固定为 `manual-react`。 |

本阶段不写入 `thinking`、`reference`、`fileid`、`recommend`。这些字段保留给未来具有明确生命周期的能力；尤其禁止保存、展示或通过 API 返回模型内部思维链。即使未来使用 `thinking`，也只能存储经设计的、非推理性的结构化运行步骤摘要。

本阶段不新增页面接口、页面组件、查询分页、会话列表、模型调用日志、分布式事务或数据库迁移。现有 `AiSessionEntity` 已经完整映射所需字段，因此无需新增表结构。

## 架构

新增独立领域端口，避免将运行轨迹混入只表达会话记忆的 `ConversationMemory`：

```text
ManualReactAgent
    ├── ConversationMemory
    │       └── 读取最近五轮 question / answer
    └── SuccessfulAgentRunPersistence
            └── MyBatisSuccessfulAgentRunPersistence
                    └── AiSessionMapper.insert
                            └── ai_session 的一条完整成功运行记录
```

### SuccessfulAgentRun

新增不可变领域记录 `SuccessfulAgentRun`，其字段为：

- `conversationId`
- `question`
- `answer`
- `executedToolNames`
- `firstResponseTimeMillis`
- `totalResponseTimeMillis`
- `agentType`

构造器拒绝空白会话编号、问题、回答和 Agent 类型，拒绝负耗时，并冻结工具名列表。工具名可以为空，表示模型直接给出了最终回答。该类型不包含 `thinking` 或任何模型隐藏推理字段。

### SuccessfulAgentRunPersistence

端口只定义 `persist(SuccessfulAgentRun run)`。调用成功意味着完整记录已经持久化；抛出异常意味着没有向用户发布成功最终回答。端口不提供读取方法，防止运行轨迹被误用为模型上下文。

### MyBatis 实现

`MyBatisSuccessfulAgentRunPersistence` 注入 `AiSessionMapper`，创建一个 `AiSessionEntity` 并在一次 `insert` 中填充本阶段字段。

`tools` 使用稳定 JSON 数组，例如 `["weather","calculator"]`；使用固定的 JSON 序列化方式，空列表写为 `[]`，而不是 `null` 或逗号分隔文本。未在本阶段启用的实体字段保持 `null`，不使用空字符串伪造数据。

`MySqlConversationMemory` 保持现有职责：按 `session_id` 查询最近五轮、只转换 `question/answer`，以及清空某会话记录。即使同一张表存储轨迹，工具信息也不会进入 `ConversationTurn` 或模型消息。

## 运行数据收集

### 计时

`ManualReactAgent.runLoop` 在其 `boundedElastic` 工作者刚开始运行时使用单调时钟记录起点。单调时钟只用于计算耗时，避免系统时钟调整造成负值或跳变。

首次可观察事件的定义为：

1. 第一条真实工具调用的 `tool_start` 即将发送时；或
2. 没有工具时，最终 `text` 即将发送时。

多个工具调用时只记录第一次。重复工具调用被跳过而不会刷新首响应时间。`total_response_time` 在最终回答已通过非空校验、取消检查和唯一终止权检查后立刻计算，在写库之前固定；因此该指标反映 Agent 工作完成到可发布最终结果的耗时，而不会因数据库性能污染模型与工具运行指标。

### 工具列表

`ReactRunContext` 记录“实际执行过”的工具名：仅当调用没有被重复保护跳过，并即将进入 `AgentToolRegistry.execute` 时才加入。未知工具或工具抛出异常仍然属于一次实际执行尝试，若 Agent 后续成功总结，应保留该工具名以帮助排障。工具名按首次真实执行顺序去重。

该上下文只属于一次运行，结束后随 Agent 释放；持久化层只得到工具名称，不得到参数、Observation 或原始模型消息。

## 成功、失败与取消语义

成功终止必须按下列顺序执行：

1. 验证最终回答非空。
2. 确认运行未取消并取得 `ReactRunContext` 的唯一终止权。
3. 冻结耗时、工具名和 `manual-react` 元数据。
4. 调用 `SuccessfulAgentRunPersistence.persist`，一次插入完整记录。
5. 插入成功后发送 `text`，再发送 `complete` 并关闭 SSE 流。

`ConversationMemory.append` 不再由 Agent 成功路径单独调用；成功运行持久化的一行记录同时提供未来的问答记忆查询数据。这样避免“先写问答、再更新轨迹”导致的半成品记录。

以下路径绝不调用持久化端口：模型异常、工具上限后的强制总结失败、空最终回答、主动停止、浏览器断开、同会话并发拒绝、历史读取失败。

最终写库失败时，Agent 已经获得终止权，因此直接发送 `error`、`complete` 并关闭流；不发送 `text`，不执行第二次保存，也不创建补偿记录。一次 `insert` 要么完成一条完整成功运行记录，要么没有该次运行记录。

现有 `InMemoryTaskRegistry` 的取消顺序、`doFinally(CANCEL)` 资源释放和阻塞循环的 `boundedElastic` 调度均保持不变。取消可能中断阻塞模型调用；工作线程在任何迟到结果到达后仍依赖既有取消标记丢弃结果，因而不能持久化取消后的轨迹。

## 测试策略

实施严格遵循 TDD：每一项行为先加入失败测试并实际运行确认失败，再写最小实现使其通过。

### 领域与 Agent 单元测试

- `SuccessfulAgentRun`：校验不可变性、必填字段、非负耗时、空工具列表和工具列表冻结。
- `ManualReactAgent` 直接回答：持久化空工具列表、`manual-react` 与有效耗时，之后才发出 `text`。
- 单工具和多工具成功：按首次实际执行顺序记录工具名。
- 重复工具调用：重复签名被跳过且不重复记录。
- 工具失败后仍成功总结：记录该次已调度工具名。
- 模型失败、空回答、取消、并发拒绝和记忆读取失败：断言零次持久化调用。
- 持久化失败：断言仅输出 `error → complete`，不输出 `text`，且任务被释放。
- 线程与取消回归：保持模型、工具和持久化调用在既有 `boundedElastic` 生命周期内，且取消不产生迟到记录。

### MyBatis-Plus H2 集成测试

- 一次 `persist` 插入一条 `ai_session` 记录，并读回问题、回答、JSON 工具数组、两个耗时与 `agent_type`。
- `thinking`、`reference`、`fileid`、`recommend` 读回均为 `null`。
- 空工具列表准确写为 `[]`。
- `MySqlConversationMemory.get` 从同一记录只恢复 `ConversationTurn(question, answer)`，不触及轨迹字段。

## 验收标准

- 每个成功 Agent 运行只写入一条包含问答和轨迹的完整记录。
- 失败、取消、空回答、并发拒绝与写库失败均不留下记录，也不向用户伪装成功答案。
- 工具轨迹不进入下一次模型上下文，前端现有记忆面板也只继续显示问答。
- `thinking` 不被写入或返回。
- 既有 SSE 事件顺序、会话互斥、主动停止、客户端断开和 `boundedElastic` 隔离测试保持通过。
