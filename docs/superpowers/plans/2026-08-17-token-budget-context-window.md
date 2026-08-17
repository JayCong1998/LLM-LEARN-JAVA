# Token 预算与上下文窗口控制实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在每次模型决策前以 2,000 Token 字符近似预算生成安全、可预测的上下文快照。

**架构：** 纯 Java `CharacterTokenBudget` 识别不可拆分消息组并计算近似消耗；`ReactRunContext` 保存当前用户消息并生成裁剪快照；`ManualReactAgent` 只将快照传给模型，完整运行历史、SSE 和持久化保持不变。

**技术栈：** Java 21、Spring AI Message、Spring WebFlux、JUnit 5、AssertJ、Reactor Test。

---

## 文件结构

- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/CharacterTokenBudget.java`：估算消息组成本并生成预算内快照。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java`：记录当前用户消息并委托预算器创建只读模型快照。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`：在每次 `model.decide` 前请求预算快照，超预算时走既有错误终止。
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/CharacterTokenBudgetTest.java`：验证估算、分组和裁剪。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactRunContextTest.java`：验证保护消息和完整上下文不变。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`：验证超大当前问题不调用模型、不写成功结果。
- 创建：`dodo-agent-learn/tutorials/stages/09-token-budget-context-window.md`：记录发送前预算与响应 usage 的区别。

### 任务 1：先定义字符预算器

**文件：**
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/CharacterTokenBudget.java`
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/CharacterTokenBudgetTest.java`

- [ ] **步骤 1：编写失败测试**

测试使用真实 Spring AI `SystemMessage`、`UserMessage`、`AssistantMessage` 与 `ToolResponseMessage`，断言每四字符一 Token、消息开销、最旧历史优先丢弃，并且 ToolCall 与 ToolResponse 不会被拆开。

```java
assertThat(budget.messagesWithinBudget(groups, system, currentUser))
        .containsExactly(system, currentUser, latestToolCall, latestToolResponse);
```

- [ ] **步骤 2：运行红灯测试**

运行：`mvn -pl dodo-agent-learn -Dtest=CharacterTokenBudgetTest test`

预期：FAIL，提示 `CharacterTokenBudget` 不存在。

- [ ] **步骤 3：实现最小预算器**

实现固定 `MAX_ESTIMATED_TOKENS = 2000`、每四字符向上取整和每消息固定开销。将历史问答、工具交互和普通助手消息建模为不可拆分组，从最新到最旧加入可选组；系统提示与当前用户问题为必保留组。必保留组超限时抛出 `ContextBudgetExceededException`。

```java
public List<Message> messagesWithinBudget(List<MessageGroup> groups, Message system, UserMessage currentUser) {
    long used = estimate(system) + estimate(currentUser);
    ensureMandatoryMessagesFit(used);
    return appendNewestGroupsWithinBudget(groups, system, currentUser, used);
}
```

所有有效 Java 代码行写准确中文注释，并用块注释解释“只裁剪模型快照、不修改完整运行历史”的原因。

- [ ] **步骤 4：运行绿灯测试**

运行：`mvn -pl dodo-agent-learn -Dtest=CharacterTokenBudgetTest test`

预期：PASS，覆盖估算、边界、最新优先与工具交互原子性。

- [ ] **步骤 5：提交预算器**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/CharacterTokenBudget.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/CharacterTokenBudgetTest.java
git commit -m "feat: add character token budget"
```

### 任务 2：接入 React 运行上下文与 Agent

**文件：**
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java`
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactRunContextTest.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`

- [ ] **步骤 1：编写失败集成测试**

在上下文测试中断言预算快照裁掉最旧历史但 `messages()` 仍保留全部消息；在 Agent 测试中传入超过预算的当前问题，断言模型调用次数为零、事件严格为 `error → complete`，且成功持久化端口未收到记录。

```java
StepVerifier.create(agent.stream("budget-overflow", oversizedQuestion))
        .expectNext(AgentStreamEvent.error("上下文预算不足：系统提示和当前问题已超过 2000 Token"))
        .expectNext(AgentStreamEvent.complete())
        .verifyComplete();
assertThat(model.calls).isZero();
```

- [ ] **步骤 2：运行红灯测试**

运行：`mvn -pl dodo-agent-learn "-Dtest=ReactRunContextTest,ManualReactAgentTest" test`

预期：FAIL，因上下文尚未暴露预算快照或 Agent 仍直接传入完整消息。

- [ ] **步骤 3：实现上下文快照与 Agent 调用点**

`ReactRunContext` 在初始化时显式登记当前用户消息；增加 `messagesWithinBudget()`，它读取锁保护的完整副本并委托预算器。将两处 `model.decide(context.messages(), ...)` 改为预算快照。预算异常进入既有 `finishWithError`，不创建 text、记忆或运行轨迹。

```java
AssistantMessage assistant = model.decide(context.messagesWithinBudget(), true);
```

工具响应仍被完整写入上下文，下一轮快照由预算器原子保留或丢弃整组。

- [ ] **步骤 4：运行集成与回归测试**

运行：`mvn -pl dodo-agent-learn "-Dtest=ReactRunContextTest,ManualReactAgentTest,ManualReactAgentMemoryTest,ManualReactAgentRunTraceTest,ChatControllerTest" test`

预期：PASS；预算外历史不进入模型，取消、超时与持久化语义不变。

- [ ] **步骤 5：提交 Agent 接入**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactRunContextTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java
git commit -m "feat: enforce token budget before model decisions"
```

### 任务 3：补齐讲义并验证

**文件：**
- 创建：`dodo-agent-learn/tutorials/stages/09-token-budget-context-window.md`

- [ ] **步骤 1：编写讲义**

说明 prompt/completion/total usage 与发送前预算的区别、字符近似局限、2,000 阈值、保留与丢弃规则、工具交互原子性，以及超大必保留消息为何拒绝而非截断。

- [ ] **步骤 2：完整验证**

运行：`mvn -pl dodo-agent-learn test`

预期：PASS，所有套件的 Failures 和 Errors 均为 0。

运行：`git -C LLM-LEARN-JAVA diff --check`

预期：无输出。

- [ ] **步骤 3：提交讲义**

```bash
git add dodo-agent-learn/tutorials/stages/09-token-budget-context-window.md
git commit -m "docs: explain token budget context window"
```

## 计划自检

- 覆盖度：任务 1 覆盖估算与分组；任务 2 覆盖快照、错误和生命周期；任务 3 覆盖讲义与全量验证。
- 类型一致性：`CharacterTokenBudget` 是 `ReactRunContext.messagesWithinBudget()` 的唯一预算实现；`ManualReactAgent` 只消费该不可变快照。
- 范围：未加入真实 tokenizer、响应 usage、数据库字段或前端改造。
