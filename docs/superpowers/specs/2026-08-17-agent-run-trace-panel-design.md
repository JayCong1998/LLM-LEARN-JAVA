# Agent 运行轨迹查询与前端面板设计

## 目标

为当前 `conversationId` 查询最近成功 Agent 运行，并在学习控制台展示其安全元数据。页面用于观察和排障，不改变模型上下文、SSE 事件、取消与会话记忆语义。

## 范围

新增 `GET /api/agent/conversations/{conversationId}/runs`，返回最近十条成功运行的：`createdAt`、`tools`、`firstResponseTimeMillis`、`totalResponseTimeMillis`、`agentType`。结果按 `create_time DESC, id DESC` 排列。

不返回 `question`、`answer`、`thinking`、`reference`、`fileid`、`recommend`，不新增写入、删除、分页、会话列表或运行中状态。查询只读取阶段 6 成功写入的行；失败和取消运行没有行，因此不会显示。

## 架构

```text
Browser trace panel
  → GET /api/agent/conversations/{conversationId}/runs
  → AgentRunTraceController
  → AgentRunTraceQuery
  → MyBatisAgentRunTraceQuery
  → AiSessionMapper.selectList
  → ai_session
```

查询端口与 `ConversationMemory` 分离：前者只投影安全运行元数据，后者只投影问答历史。控制器通过 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 隔离 MyBatis/JDBC 阻塞 I/O。

## 前端行为

在现有会话记忆面板之后增加“运行轨迹”面板和“刷新轨迹”按钮。页面初次加载时读取轨迹；收到未含 error 的最终 `text → complete` 后自动刷新。每条卡片使用 DOM API 和 `textContent` 写入固定字段；工具 JSON 解析失败时按原文本显示。工具为空显示“未调用工具”。

读取失败显示面板错误状态但不影响聊天、停止或会话记忆按钮。前端不向轨迹接口提交数据，也不显示模型推理、工具参数、Observation、问题或回答。

## 测试

- 查询端口：会话隔离、倒序、最多十条、字段投影与空会话。
- 控制器：JSON 契约、500 映射和 `boundedElastic` 线程边界。
- 页面契约：容器、刷新路径、安全 `textContent`、自动刷新和思维链禁用。
- 回归：完整模块测试及 `git diff --check`。
