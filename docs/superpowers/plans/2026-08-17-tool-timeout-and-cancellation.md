# 工具超时与取消传播实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为每个真实 ReAct 工具调用提供三秒超时和取消传播，并将超时作为可回填的 Observation。

**架构：** `ManualReactAgent` 依赖窄化的 `ToolExecutionPort`；生产实现 `TimedToolExecutor` 在受管虚拟线程中调用既有 `AgentToolRegistry`，用 `Future` 等待三秒。超时返回稳定 Observation；中断取消内部 Future 并让外层 Agent 维持既有取消终止协议。

**技术栈：** Java 21、Spring Boot、Spring WebFlux、Reactor、Spring AI、JUnit 5、AssertJ、Reactor Test。

---

## 文件结构

- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java`：定义 Agent 可替换的同步工具执行边界。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/TimedToolExecutor.java`：使用虚拟线程、三秒 `Future.get`、超时与中断取消实现该端口。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`：改为依赖执行端口，并在工具返回后防止取消后的迟到事件和上下文回填。
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/TimedToolExecutorTest.java`：测试执行器的结果、超时和中断语义。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`：测试超时 Observation 的 SSE/模型回填，以及取消等待中的工具时没有迟到事件。
- 创建：`dodo-agent-learn/tutorials/stages/08-tool-timeout-and-cancellation.md`：记录线程、超时、协作式中断与持久化边界。

### 任务 1：先定义并验证限时工具执行边界

**文件：**
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/TimedToolExecutor.java`
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/TimedToolExecutorTest.java`

- [ ] **步骤 1：编写失败的执行器测试**

在 `TimedToolExecutorTest` 创建受控 `ToolCallback`，覆盖快速成功、等待超时后被中断、以及等待线程被中断后内部任务被取消。测试构造器传入 50 毫秒短超时，生产默认值仍为三秒。

```java
@Test
void returnsTimeoutObservationAndInterruptsSlowTool() throws Exception {
    CountDownLatch interrupted = new CountDownLatch(1);
    TimedToolExecutor executor = executor(arguments -> {
        try {
            new CountDownLatch(1).await();
            return "不应返回";
        } catch (InterruptedException error) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            return "迟到结果";
        }
    }, Duration.ofMillis(50));

    assertThat(executor.execute("slow", "{}")).isEqualTo("工具执行超时：slow");
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl dodo-agent-learn -Dtest=TimedToolExecutorTest test`

预期：FAIL，提示 `TimedToolExecutor` 或 `ToolExecutionPort` 不存在。

- [ ] **步骤 3：实现最小执行端口和限时执行器**

定义端口和生产实现；所有有效 Java 代码行添加准确中文注释，并在执行器中用块注释解释“超时、外层中断和协作式中断”的资源边界。

```java
public interface ToolExecutionPort {
    String execute(String toolName, String arguments);
}

@Component
public class TimedToolExecutor implements ToolExecutionPort, AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    @Override
    public String execute(String toolName, String arguments) {
        Future<String> future = executor.submit(() -> registry.execute(toolName, arguments));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            return "工具执行超时：" + toolName;
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolExecutionInterruptedException(error);
        }
    }
}
```

实现还必须处理 `ExecutionException`：转换为现有风格的失败 Observation，而不是把工具工作线程异常泄漏给 SSE。`ToolExecutionInterruptedException` 定义为 `TimedToolExecutor` 内部私有运行时异常，用于保留外层中断语义；通过 `@PreDestroy` 或 `close()` 关闭虚拟线程执行器；测试在 `finally` 中关闭其执行器。

- [ ] **步骤 4：运行执行器测试验证通过**

运行：`mvn -pl dodo-agent-learn -Dtest=TimedToolExecutorTest test`

预期：PASS，三个测试均通过，且无等待中的非守护线程阻塞 Maven 退出。

- [ ] **步骤 5：提交执行器边界**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/TimedToolExecutor.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/TimedToolExecutorTest.java
git commit -m "feat: add timed tool executor"
```

### 任务 2：把超时与取消接入 Manual ReAct 循环

**文件：**
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`

- [ ] **步骤 1：编写失败的 ReAct 集成测试**

新增两个测试：其一使用测试 `ToolExecutionPort` 返回 `工具执行超时：weather`，断言 `tool_start → tool_end → text → complete`，并断言第二轮模型上下文包含相同 Observation；其二让端口阻塞直到收到中断，触发 `tasks.cancel` 后断言只收到取消的 `error → complete`，没有 `tool_end` 或 `text`。

```java
StepVerifier.create(agent.stream("timeout-conversation", "查询天气"))
        .expectNext(AgentStreamEvent.toolStart("weather", "call-1", "{}"))
        .expectNext(AgentStreamEvent.toolEnd("weather", "call-1", "工具执行超时：weather"))
        .expectNext(AgentStreamEvent.text("天气工具超时，我先给出替代说明。"))
        .expectNext(AgentStreamEvent.complete())
        .verifyComplete();

assertThat(toolResponse.getResponses().getFirst().responseData())
        .isEqualTo("工具执行超时：weather");
```

- [ ] **步骤 2：运行集成测试验证失败**

运行：`mvn -pl dodo-agent-learn -Dtest=ManualReactAgentTest test`

预期：FAIL，因 Agent 尚未接收 `ToolExecutionPort`，或取消后仍可能发送 `tool_end`。

- [ ] **步骤 3：最小化改造 Agent 依赖与取消检查**

将生产构造器注入 `ToolExecutionPort`，保留不依赖 Spring 的测试组装入口，并把真实首次调用从注册表直接执行替换为端口调用。端口返回后立即检查取消状态；取消已取得终止权时直接退出本轮，禁止输出迟到 `tool_end`、追加 `ToolResponseMessage` 或再次调用模型。

```java
observation = toolExecutor.execute(toolCall.name(), toolCall.arguments());
if (context.isCancelled()) {
    return;
}
output.tryEmitNext(AgentStreamEvent.toolEnd(toolCall.name(), toolCall.id(), observation));
responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), observation));
```

让 `executeToolCalls` 返回是否因取消提前结束；`runLoop` 收到该结果后立即返回，避免在被取消的运行中把空响应列表回填上下文。同步更新所有构造器调用和测试工厂，测试默认使用直接委托 `AgentToolRegistry.execute` 的端口，只有超时和取消场景注入专用端口。

- [ ] **步骤 4：运行集成与相关回归测试验证通过**

运行：`mvn -pl dodo-agent-learn -Dtest=ManualReactAgentTest,ManualReactAgentRunTraceTest,ChatControllerTest test`

预期：PASS；超时仍能完成成功运行，主动取消仍只发一次取消终止协议。

- [ ] **步骤 5：提交 ReAct 接入**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java
git commit -m "feat: propagate tool timeout and cancellation"
```

### 任务 3：完成讲义与全量验证

**文件：**
- 创建：`dodo-agent-learn/tutorials/stages/08-tool-timeout-and-cancellation.md`

- [ ] **步骤 1：编写阶段讲义**

讲义必须说明：为什么模型工作线程与工具虚拟线程分离；三秒超时如何变成 Observation；用户停止的取消传播路径；`Future.cancel(true)` 的协作式中断限制；为什么超时可以继续而取消绝不能写成功记忆或运行轨迹。

- [ ] **步骤 2：执行完整验证**

运行：`mvn -pl dodo-agent-learn test`

预期：PASS，全部模块测试无 `Failures` 或 `Errors`。

运行：`git -C LLM-LEARN-JAVA diff --check`

预期：无输出。

运行：`git -C LLM-LEARN-JAVA status --short`

预期：仅显示本讲义文件。

- [ ] **步骤 3：提交讲义和验证结果**

```bash
git add dodo-agent-learn/tutorials/stages/08-tool-timeout-and-cancellation.md
git commit -m "docs: explain tool timeout and cancellation"
```

- [ ] **步骤 4：最终工作区检查**

运行：`git -C LLM-LEARN-JAVA status --short`

预期：无输出；仅本阶段提交位于 `master`。

## 计划自检

- 规格覆盖：任务 1 覆盖三秒限时、虚拟线程、超时与中断；任务 2 覆盖 SSE、Observation 回填、取消和持久化不回归；任务 3 覆盖讲义与完整验证。
- 占位符：计划中没有待定实现或未定义的后续工作；`ToolExecutionPort`、`TimedToolExecutor` 和 `ToolExecutionInterruptedException` 均在任务 1 明确创建或定义。
- 类型一致性：`ManualReactAgent` 只依赖 `ToolExecutionPort.execute(String, String)`；生产 `TimedToolExecutor` 和测试 Lambda 均实现该签名。
