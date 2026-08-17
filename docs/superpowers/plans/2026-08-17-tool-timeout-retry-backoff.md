# 工具超时重试与退避实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 仅在工具单次执行超时时，以 200 ms、400 ms 指数退避最多额外重试 2 次，并通过 SSE 与前端安全展示重试状态。

**架构：** `RetryingToolExecutor` 是生产环境的 `@Primary ToolExecutionPort`，包装现有单次限时的 `TimedToolExecutor`。端口增加带默认实现的重试通知重载，以保持现有 Lambda 测试兼容。 `ManualReactAgent` 继续拥有 SSE 输出权，仅把重试器通知转为 `tool_retry` 事件。

**技术栈：** Java 21、Spring Boot、Spring AI、Reactor SSE、JUnit 5、AssertJ、原生 JavaScript。

---

## 文件结构

- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolRetryListener.java`，定义重试通知窄接口。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/RetryingToolExecutor.java`，负责超时识别、退避和重试。
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/RetryingToolExecutorTest.java`，验证可靠性策略。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java`，保留单抽象方法并增加默认通知重载。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java`，添加 `tool_retry` 协议字段。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`，映射重试通知并守住取消边界。
- 修改：`dodo-agent-learn/src/main/resources/static/js/app.js`，显示工具卡片重试状态。
- 创建：`dodo-agent-learn/tutorials/stages/10-tool-timeout-retry-backoff.md`，记录本阶段教学边界。

### 任务 1：定义兼容的重试通知端口

**文件：**

- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolRetryListener.java`
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java`
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/RetryingToolExecutorTest.java`

- [ ] **步骤 1：编写失败的兼容性测试**

```java
@Test
void defaultRetryOverloadKeepsLambdaPortCompatible() {
    ToolExecutionPort port = (toolName, arguments) -> "正常结果";
    List<String> notifications = new ArrayList<>();

    String observation = port.execute("weather", "{}", (attempt, delayMillis) -> notifications.add(attempt + ":" + delayMillis));

    assertThat(observation).isEqualTo("正常结果");
    assertThat(notifications).isEmpty();
}
```

- [ ] **步骤 2：运行测试确认红灯**

运行：`mvn -pl dodo-agent-learn -Dtest=RetryingToolExecutorTest test`

预期：FAIL，提示 `ToolRetryListener` 或三参数 `execute` 不存在。

- [ ] **步骤 3：实现最小端口扩展**

```java
@FunctionalInterface
public interface ToolRetryListener {
    void onRetry(int attempt, long delayMillis);
}

default String execute(String toolName, String arguments, ToolRetryListener retryListener) {
    return execute(toolName, arguments);
}
```

每一行有效 Java 代码添加准确中文注释；默认方法不能变成抽象方法。

- [ ] **步骤 4：运行测试确认转绿**

运行：`mvn -pl dodo-agent-learn -Dtest=RetryingToolExecutorTest test`

预期：PASS；旧 Lambda 端口仍正常返回 Observation。

- [ ] **步骤 5：提交端口边界**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolRetryListener.java dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/RetryingToolExecutorTest.java
git commit -m "feat: add tool retry notification port"
```

### 任务 2：实现仅超时触发的退避重试器

**文件：**

- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/RetryingToolExecutor.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/RetryingToolExecutorTest.java`

- [ ] **步骤 1：编写失败的可靠性测试**

使用脚本化 `ToolExecutionPort` 和包内 `Sleeper` 测试替身，新增以下独立测试：首次、第二次超时后第三次成功；第二次成功；连续 3 次超时；`工具执行失败：bad request` 与 `未知工具：weather` 不重试；退避等待被中断时不开始下一次尝试。

核心成功断言：

```java
assertThat(executor.execute("weather", "{}", listener)).isEqualTo("最终成功");
assertThat(delegateCalls).isEqualTo(3);
assertThat(notifications).containsExactly("2:200", "3:400");
assertThat(delays).containsExactly(200L, 400L);
```

- [ ] **步骤 2：运行测试确认红灯**

运行：`mvn -pl dodo-agent-learn -Dtest=RetryingToolExecutorTest test`

预期：FAIL，提示 `RetryingToolExecutor` 不存在。

- [ ] **步骤 3：实现最小重试器**

创建 `@Component`、`@Primary` 的 `RetryingToolExecutor`，生产构造器注入具体 `TimedToolExecutor` 而非 `ToolExecutionPort`，避免自引用依赖。生产等待使用 `Thread::sleep`，包内构造器接收 `Sleeper`。

```java
String observation = delegate.execute(toolName, arguments);
for (int attempt = 2; isTimeout(observation, toolName) && attempt <= 3; attempt++) {
    long delayMillis = attempt == 2 ? 200L : 400L;
    retryListener.onRetry(attempt, delayMillis);
    sleeper.sleep(delayMillis);
    observation = delegate.execute(toolName, arguments);
}
return observation;
```

`isTimeout` 严格比较 `"工具执行超时：" + toolName`。 `InterruptedException` 必须恢复中断标记并抛出运行时取消异常，不能吞掉中断、不能开始下一次执行。类级块注释说明单次限时和多次可靠性职责分离；所有新增或修改有效 Java 代码均添加中文注释。

- [ ] **步骤 4：运行测试确认转绿**

运行：`mvn -pl dodo-agent-learn '-Dtest=RetryingToolExecutorTest,TimedToolExecutorTest' test`

预期：PASS；单次超时行为未变，重试器只对超时执行最多 3 次尝试。

- [ ] **步骤 5：提交重试实现**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/RetryingToolExecutor.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/RetryingToolExecutorTest.java
git commit -m "feat: retry timed out tools with backoff"
```

### 任务 3：扩展 SSE 协议并接入 Agent

**文件：**

- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java`
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`

- [ ] **步骤 1：编写失败的协议和 Agent 测试**

将 record 预期扩展为 7 字段，并添加：

```java
assertThat(AgentStreamEvent.toolRetry("weather", "call-1", 2, 200L))
        .isEqualTo(new AgentStreamEvent("tool_retry", "", "weather", "call-1", null, 2, 200L));
```

在 `ManualReactAgentTest` 注入首次超时、第二次成功的脚本端口，断言时序：

```text
tool_start → tool_retry(attempt=2, delay=200) → tool_end("晴朗") → text → complete
```

断言下一轮 `ToolResponseMessage.responseData()` 是 `晴朗`。另写退避阻塞期间取消测试：取消后无第二次工具执行、无迟到 `tool_end`、无后续模型调用。

- [ ] **步骤 2：运行测试确认红灯**

运行：`mvn -pl dodo-agent-learn '-Dtest=AgentStreamEventTest,ManualReactAgentTest' test`

预期：FAIL，提示 `toolRetry`、record 字段或通知映射不存在。

- [ ] **步骤 3：实现协议和 Agent 映射**

```java
public record AgentStreamEvent(
        String type, String content, String toolName, String toolCallId,
        String arguments, Integer attempt, Long delayMillis) { }

observation = toolExecutor.execute(toolCall.name(), toolCall.arguments(), (attempt, delayMillis) -> {
    if (!context.isCancelled()) {
        output.tryEmitNext(AgentStreamEvent.toolRetry(toolCall.name(), toolCall.id(), attempt, delayMillis));
    }
});
```

所有旧工厂的新增字段传入 `null`； `toolRetry` 不包含参数或 Observation。保留工具返回后的取消检查；重试器等待被中断时，取消回调已拥有唯一终止权。每行有效代码使用中文注释，生命周期与取消分支使用块注释。

- [ ] **步骤 4：运行测试确认转绿**

运行：`mvn -pl dodo-agent-learn '-Dtest=AgentStreamEventTest,ManualReactAgentTest,ChatControllerTest' test`

预期：PASS；既有 SSE 序列化、超时 Observation、并发拒绝与取消测试均通过。

- [ ] **步骤 5：提交 SSE 和 Agent 接入**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java
git commit -m "feat: expose tool retry events"
```

### 任务 4：显示工具卡片重试状态

**文件：**

- 修改：`dodo-agent-learn/src/main/resources/static/js/app.js`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/LearningConsoleContractTest.java`

- [ ] **步骤 1：编写失败的前端契约测试**

```java
assertThat(javascript)
        .contains("event.type === 'tool_retry'")
        .contains("handleToolRetry(event)")
        .contains("event.attempt")
        .contains("event.delayMillis");
```

保留 `doesNotContain("Chain of Thought", "思维链")`。

- [ ] **步骤 2：运行测试确认红灯**

运行：`mvn -pl dodo-agent-learn -Dtest=LearningConsoleContractTest test`

预期：FAIL，提示脚本尚未处理 `tool_retry`。

- [ ] **步骤 3：实现安全状态更新**

```javascript
} else if (event.type === 'tool_retry') {
    handleToolRetry(event);
}
```

新增 `handleToolRetry`：使用 `event.toolCallId` 查找已有工具卡片；找不到时安全忽略；找到时用 `textContent` 更新状态为 `第 ${event.attempt} 次重试前等待 ${event.delayMillis} ms`。每一行新增或修改有效 JavaScript 代码添加中文注释；不得把模型内容作为 HTML 插入。

- [ ] **步骤 4：运行测试确认转绿并提交**

运行：`mvn -pl dodo-agent-learn -Dtest=LearningConsoleContractTest test`

预期：PASS。

```bash
git add dodo-agent-learn/src/main/resources/static/js/app.js dodo-agent-learn/src/test/java/com/jaycong/dodo/web/LearningConsoleContractTest.java
git commit -m "feat: show tool retry status in console"
```

### 任务 5：教程与全量验证

**文件：**

- 创建：`dodo-agent-learn/tutorials/stages/10-tool-timeout-retry-backoff.md`

- [ ] **步骤 1：编写教程**

说明 `TimedToolExecutor` 的单次 3 秒上限和 `RetryingToolExecutor` 的超时可靠性职责；最多 3 次尝试、200 ms/400 ms 退避、`tool_start → tool_retry → tool_end` 时序、最终 Observation 回填、取消可中断退避，以及不重试业务异常/不含抖动熔断限流的范围。

- [ ] **步骤 2：运行完整验证**

运行：`mvn -pl dodo-agent-learn test`、`git diff --check`、`git status --short`

预期：Maven 输出 `BUILD SUCCESS` 且 `Failures: 0, Errors: 0`；无空白错误；状态只包含教程文件。

- [ ] **步骤 3：提交教程**

```bash
git add dodo-agent-learn/tutorials/stages/10-tool-timeout-retry-backoff.md
git commit -m "docs: explain tool timeout retry backoff"
git status --short
```

预期：最终工作区干净。

## 计划自检

- 规格覆盖度：任务 1 至 2 覆盖接口、严格超时识别、最多 3 次尝试、固定退避与中断；任务 3 覆盖 SSE、最终 Observation 与取消；任务 4 覆盖页面；任务 5 覆盖教程和完整验证。
- 占位符扫描：无 TODO、待定项或未定义类型； `ToolRetryListener`、`RetryingToolExecutor`、`Sleeper` 均在任务中明确。
- 类型一致性：全计划统一使用 `onRetry(int attempt, long delayMillis)`、三参数 `execute` 和 7 字段 `AgentStreamEvent`。
