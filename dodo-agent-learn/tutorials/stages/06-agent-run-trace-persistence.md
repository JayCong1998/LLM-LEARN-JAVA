# 阶段 6：Agent 运行轨迹持久化

> 状态：已完成  
> 目标：仅在一次 `ManualReactAgent` 成功结束后，将问答、工具名称和性能指标一次性写入 `ai_session`。

## 为什么新增端口

`ConversationMemory` 的职责仍是读取最近五轮 `question/answer` 并在下一次请求中回放它们。工具调用、参数、Observation、耗时和模型隐藏推理都不是对话记忆，不能进入模型上下文。

因此阶段 6 新增：

```text
ManualReactAgent
  ├─ ConversationMemory.get()：只读取历史问答
  └─ SuccessfulAgentRunPersistence.persist()：只写一条完整成功运行
       └─ MyBatisSuccessfulAgentRunPersistence
            └─ AiSessionMapper.insert(ai_session)
```

`SuccessfulAgentRun` 是不可变记录，包含会话编号、问题、回答、按首次执行顺序去重的工具名、首响应耗时、总耗时和 `manual-react` 类型。它拒绝空白必填字段、负耗时和空工具列表引用，并冻结工具列表。

## 哪些字段会写入

| `ai_session` 字段 | 本阶段值 |
| --- | --- |
| `session_id`、`question`、`answer` | 本次成功问答 |
| `tools` | JSON 数组，如 `["weather","calculator"]`；直接回答为 `[]` |
| `first_response_time` | 工作线程开始到首个 `tool_start` 或 `text` 前的毫秒数 |
| `total_response_time` | 工作线程开始到最终回答就绪、写库前的毫秒数 |
| `agent_type` | `manual-react` |
| `thinking`、`reference`、`fileid`、`recommend` | 保持 `null` |

`System.nanoTime()` 只用于相减得到单调耗时，不受系统时间校准影响。总耗时不含本次插入本身，避免为了更新一个性能字段形成第二次数据库操作。

## 成功与失败边界

成功路径顺序：

```text
最终回答非空
→ 取得唯一终止权
→ 冻结工具名与耗时
→ Mapper 一次 insert 完整记录
→ text
→ complete
```

因此客户端看见 `text` 时，对应成功记录已经存在。模型失败、空回答、取消、浏览器断开、同会话并发拒绝、历史读取失败和运行轨迹写库失败都不产生记录。写库失败时仅输出 `error → complete`，绝不输出尚未成功保存的 `text`。

工具名只在第一次真正进入 `AgentToolRegistry.execute` 前记录；同参数重复调用会生成既有的跳过 Observation，但不会重复写入轨迹。未知工具和工具异常仍是一次真实执行尝试，若 Agent 最终成功总结，则名称保留用于排障。

## 核心伪代码

```text
runLoop:
    startedAt = nanoTime()
    load ConversationMemory question/answer snapshot
    while can decide:
        assistant = model.decide(...)
        if assistant has tool calls:
            for call in calls:
                if first real execution:
                    record first response if absent
                    add tool name once
                    emit tool_start
                    execute tool
                else:
                    emit tool_start and skip observation
                emit tool_end
            append ToolResponseMessage
        else:
            finishSuccessfully()

finishSuccessfully:
    reject blank answer
    acquire finish gate
    record first response if absent
    persist SuccessfulAgentRun once
    emit text, complete
```

本阶段不保存或展示 Chain of Thought。若未来需要 `thinking` 字段，只能写入人工定义的、安全结构化步骤摘要，不能记录模型隐藏推理。

## 测试策略

- `SuccessfulAgentRunTest`：校验、不可变工具列表与耗时边界。
- `MyBatisSuccessfulAgentRunPersistenceTest`：H2 上验证一次 Mapper 插入、JSON 工具名和保留字段为空。
- `ManualReactAgentRunTraceTest`：验证成功记录在 `text` 前完成，直接回答使用空工具列表。
- 既有 Agent、Web、取消和会话记忆测试：验证 SSE、`boundedElastic`、并发与历史回放不回归。
