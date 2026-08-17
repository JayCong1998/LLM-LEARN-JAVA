# 阶段 9：Token 预算与上下文窗口控制

## 目标

在每次调用模型前，为 ReAct 消息构造一个不超过固定预算的快照。本阶段默认预算是 **2,000 个估算 Token**；它优先保留系统提示、当前用户问题和最近的完整上下文组。

这不是模型专用 tokenizer，也不是模型 API 返回的真实用量统计。它是请求发出前的本地保护：在不知道不同模型真实上下文窗口时，避免完整运行历史无上限增长。

## 实际用量与发送前预算的区别

模型响应中的 `usage` 可以告诉我们一次请求已经消耗了多少 input、output 和 total token，但那是请求完成后的事实，无法阻止超窗口请求，也不能帮助选择哪些历史应该随下一轮请求发送。

本阶段用如下稳定近似规则在发送前计算：

```text
单条消息估算 Token
= 4 个固定协议 Token
+ ceil(消息及工具协议字段字符数 / 4)
```

除可见文本外，助手工具调用的 id、type、name、arguments，以及工具响应的 id、name、responseData 都会计入。原因是这些字段也会被发送给模型；只估算普通文本会低估多工具 ReAct 的实际上下文大小。

后续如接入确定模型，可以将 `CharacterTokenBudget` 替换为该模型的官方 tokenizer。替换时仍要保留本阶段的“先组后裁剪”语义。

## 两份消息视图

`ReactRunContext` 继续保存完整运行历史，供工具去重、运行轨迹与生命周期管理使用。新增的 `messagesWithinBudget()` 只创建给 `model.decide(...)` 的不可变快照：

```text
完整 ReactRunContext.messages()
        │ 不改写
        ▼
CharacterTokenBudget.messagesWithinBudget(...)
        │ 裁剪后的只读快照
        ▼
model.decide(snapshot, toolsEnabled)
```

因此，工具 Observation 可以在本次运行的完整上下文中继续存在，却不会进入 `ConversationMemory`。跨请求记忆仍然只有成功的 `question / answer`，工具参数、工具结果和运行轨迹都不会变成下一次用户对话的历史。

## 保留与裁剪规则

系统提示与本轮显式当前用户问题是必需消息，绝不裁剪。其余消息会先划分为原子组，再从最新组向最旧组选择能够完整放入剩余预算的组：

- 持久化历史中的 `UserMessage + AssistantMessage` 是一组；不会只留下回答或问题。
- `AssistantMessage` 的工具调用与紧随其后的 `ToolResponseMessage` 是一组；不会只留下 Action 或 Observation 的一半。
- 其他普通助手消息或强制收尾指令按单条组成一组。

最后按原始时间顺序拼回快照。这样既优先近期内容，又不破坏问答轮次和 ReAct 工具协议的关联。

## 必需消息本身超预算

如果系统提示和当前问题合计已经超过预算，不能静默截断当前问题。Agent 不会调用模型，而是沿用现有 SSE 终止协议：

```text
error("上下文预算不足：系统提示和当前问题已超过 2000 Token")
→ complete
```

这条失败路径不会产生 `text`，不会保存成功会话记忆，也不会写入成功 Agent 运行轨迹。取消、同会话并发拒绝、`boundedElastic` 调度和工具超时的语义不因预算器而改变。

## 测试重点

- `CharacterTokenBudgetTest` 验证字符向上取整、最近完整历史优先、工具 Action 与 Observation 的原子性，以及必需消息超预算拒绝。
- `ReactRunContextTest` 验证预算快照不会修改完整运行历史。
- `ManualReactAgentTest` 验证超预算时模型端口从未被调用，且不会写入会话记忆。

本阶段的价值不在于精确核算账单，而在于把“上下文为什么被保留或丢弃”变成确定、可观察、可回归的 Agent 运行规则。
