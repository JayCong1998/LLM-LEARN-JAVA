# Agent 运行轨迹持久化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 仅为成功的 `ManualReactAgent` 运行原子写入包含问答、工具名、性能指标和 Agent 类型的 `ai_session` 记录。

**架构：** `ConversationMemory` 继续只读取和清空问答窗口；新增 `SuccessfulAgentRunPersistence` 端口和 MyBatis-Plus 实现一次插入完整成功记录。`ManualReactAgent` 在 `boundedElastic` 工作线程收集安全运行元数据，持久化成功后才发送最终 `text`。

**技术栈：** Java 21、Spring Boot 3.5、WebFlux、Project Reactor、MyBatis-Plus 3.5.17、H2、JUnit 5、AssertJ。

---

## 文件结构

- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/SuccessfulAgentRun.java`——完整成功运行的不可变领域数据。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/SuccessfulAgentRunPersistence.java`——只写入完整运行的领域端口。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/MyBatisSuccessfulAgentRunPersistence.java`——使用 Mapper 单次插入 `ai_session` 的适配器。
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/SuccessfulAgentRunTest.java`——领域记录的校验和不可变性测试。
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/MyBatisSuccessfulAgentRunPersistenceTest.java`——H2/MyBatis-Plus 真实持久化测试。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java`——保存实际执行工具名并返回不可变快照。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`——注入持久化端口、采集单调耗时并替换成功保存路径。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java`——将旧问答追加断言替换为完整运行持久化、失败和取消断言。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`——为所有直接构造的 Agent 提供记录型运行持久化端口。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java`——在 Web 切片中提供 `SuccessfulAgentRunPersistence` 测试 Bean。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`——验证同一完整记录仍只被恢复为问答记忆。
- 创建：`dodo-agent-learn/tutorials/stages/06-agent-run-trace-persistence.md`——记录阶段原理、边界和伪代码。

### 任务 1：定义完整成功运行领域契约

**文件：**
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/SuccessfulAgentRunTest.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/SuccessfulAgentRun.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/SuccessfulAgentRunPersistence.java`

- [ ] **步骤 1：编写失败的领域测试**

```java
assertThat(new SuccessfulAgentRun("c-1", "问题", "回答", List.of("weather"), 12L, 34L, "manual-react")
        .executedToolNames()).containsExactly("weather");
assertThatThrownBy(() -> new SuccessfulAgentRun(" ", "问题", "回答", List.of(), 0L, 0L, "manual-react"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("会话编号不能为空");
assertThatThrownBy(() -> new SuccessfulAgentRun("c-1", "问题", "回答", List.of(), -1L, 0L, "manual-react"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("首响应耗时不能为负数");
```

- [ ] **步骤 2：运行失败测试验证红灯**

运行：`mvn -pl dodo-agent-learn -Dtest=SuccessfulAgentRunTest test`

预期：FAIL，编译错误指出 `SuccessfulAgentRun` 不存在。

- [ ] **步骤 3：实现最小领域类型与写入端口**

```java
public record SuccessfulAgentRun(
        String conversationId,
        String question,
        String answer,
        List<String> executedToolNames,
        long firstResponseTimeMillis,
        long totalResponseTimeMillis,
        String agentType) {
    public SuccessfulAgentRun {
        validateNotBlank(conversationId, "会话编号不能为空");
        validateNotBlank(question, "用户问题不能为空");
        validateNotBlank(answer, "最终回答不能为空");
        validateNotBlank(agentType, "Agent 类型不能为空");
        if (firstResponseTimeMillis < 0L) throw new IllegalArgumentException("首响应耗时不能为负数");
        if (totalResponseTimeMillis < 0L) throw new IllegalArgumentException("总响应耗时不能为负数");
        executedToolNames = List.copyOf(executedToolNames);
    }
}

public interface SuccessfulAgentRunPersistence {
    void persist(SuccessfulAgentRun run);
}
```

每一行有效 Java 代码补充具体中文注释；对记录构造器中的校验和列表冻结写块注释，说明它们防止脏轨迹跨越领域边界。

- [ ] **步骤 4：运行领域测试验证绿灯**

运行：`mvn -pl dodo-agent-learn -Dtest=SuccessfulAgentRunTest test`

预期：PASS。

- [ ] **步骤 5：提交领域契约**

运行：`git add dodo-agent-learn/src/main/java/com/jaycong/dodo/trace dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/SuccessfulAgentRunTest.java && git commit -m "feat: define successful agent run trace"`

### 任务 2：以真实 Mapper 写入完整轨迹

**文件：**
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/MyBatisSuccessfulAgentRunPersistenceTest.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/MyBatisSuccessfulAgentRunPersistence.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`

- [ ] **步骤 1：编写 H2/MyBatis-Plus 失败测试**

```java
SuccessfulAgentRun run = new SuccessfulAgentRun(
        "trace-session", "问题", "回答", List.of("weather", "calculator"), 18L, 52L, "manual-react");
persistence.persist(run);
AiSessionEntity row = aiSessionMapper.selectList(null).getOnly();
assertThat(row.getTools()).isEqualTo("[\"weather\",\"calculator\"]");
assertThat(row.getFirstResponseTime()).isEqualTo(18L);
assertThat(row.getTotalResponseTime()).isEqualTo(52L);
assertThat(row.getAgentType()).isEqualTo("manual-react");
assertThat(row.getThinking()).isNull();
assertThat(new MySqlConversationMemory(aiSessionMapper).get("trace-session"))
        .containsExactly(new ConversationTurn("问题", "回答"));
```

- [ ] **步骤 2：运行失败测试验证红灯**

运行：`mvn -pl dodo-agent-learn -Dtest=MyBatisSuccessfulAgentRunPersistenceTest test`

预期：FAIL，编译错误指出 `MyBatisSuccessfulAgentRunPersistence` 不存在。

- [ ] **步骤 3：实现单次插入适配器**

```java
@Component
public class MyBatisSuccessfulAgentRunPersistence implements SuccessfulAgentRunPersistence {
    private final AiSessionMapper aiSessionMapper;

    @Override
    public void persist(SuccessfulAgentRun run) {
        AiSessionEntity entity = new AiSessionEntity();
        entity.setSessionId(run.conversationId());
        entity.setQuestion(run.question());
        entity.setAnswer(run.answer());
        entity.setTools(toToolsJson(run.executedToolNames()));
        entity.setFirstResponseTime(run.firstResponseTimeMillis());
        entity.setTotalResponseTime(run.totalResponseTimeMillis());
        entity.setAgentType(run.agentType());
        aiSessionMapper.insert(entity);
    }
}
```

使用 Jackson 的 `ObjectMapper` 序列化工具列表；序列化失败转换成 `IllegalStateException`，并且不调用第二次插入。禁止写入 `thinking`、`reference`、`fileId`、`recommend`。每行有效 Java 代码补充具体中文注释。

- [ ] **步骤 4：运行持久化和记忆回归测试验证绿灯**

运行：`mvn -pl dodo-agent-learn -Dtest=MyBatisSuccessfulAgentRunPersistenceTest,MySqlConversationMemoryTest test`

预期：PASS，完整字段只出现于一条记录，记忆读取仍只回放问答。

- [ ] **步骤 5：提交 MyBatis 适配器**

运行：`git add dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/MyBatisSuccessfulAgentRunPersistence.java dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/MyBatisSuccessfulAgentRunPersistenceTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java && git commit -m "feat: persist successful agent run traces"`

### 任务 3：让 ReAct 成功路径收集并提交轨迹

**文件：**
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java`
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java`

- [ ] **步骤 1：编写失败的 Agent 测试**

```java
assertNext(event -> {
    assertThat(event).isEqualTo(AgentStreamEvent.text("最终回答"));
    assertThat(persistence.runs).singleElement().satisfies(run -> {
        assertThat(run.executedToolNames()).isEmpty();
        assertThat(run.agentType()).isEqualTo("manual-react");
        assertThat(run.firstResponseTimeMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(run.totalResponseTimeMillis()).isGreaterThanOrEqualTo(run.firstResponseTimeMillis());
    });
});
```

为单工具、多工具、重复调用、模型异常、空回答、取消、并发拒绝、历史读取失败和持久化失败分别增加断言。失败与取消场景断言 `persistence.runs` 为空；持久化失败断言事件严格为 `error → complete` 且无 `text`。

- [ ] **步骤 2：运行 Agent 测试验证红灯**

运行：`mvn -pl dodo-agent-learn -Dtest=ManualReactAgentMemoryTest,ManualReactAgentTest,ChatControllerTest test`

预期：FAIL，构造器尚未注入 `SuccessfulAgentRunPersistence`，且上下文没有工具名快照。

- [ ] **步骤 3：最小化扩展上下文与 Agent**

```java
public synchronized boolean markToolExecution(String toolName, String arguments) {
    boolean firstExecution = executedToolSignatures.add(signature(toolName, arguments));
    if (firstExecution) executedToolNames.add(toolName);
    return firstExecution;
}

private void markFirstObservableResponse(ReactRunContext context, long startedAtNanos) {
    context.recordFirstResponseTimeMillisIfAbsent(elapsedMillis(startedAtNanos));
}

private void finishSuccessfully(...) {
    if (context.tryFinish()) {
        successfulRuns.persist(new SuccessfulAgentRun(...));
        tasks.complete(conversationId);
        output.tryEmitNext(AgentStreamEvent.text(answer));
        output.tryEmitNext(AgentStreamEvent.complete());
        output.tryEmitComplete();
    }
}
```

在 `runLoop` 的第一行记录 `System.nanoTime()`；发送第一条 `tool_start` 前或直接回答的 `text` 前记录首响应。`finishSuccessfully` 先校验非空回答、获得终止权、持久化完整 `SuccessfulAgentRun`，成功后才发送文本。持久化异常直接发送稳定错误和完成事件，绝不回退到 `ConversationMemory.append`。保留 `initializeMessages` 中的 `ConversationMemory.get`，不将工具名、参数、Observation 或计时写入模型消息。为生产构造器显式标记 `@Autowired`，并把 `ManualReactAgentTest` 的每个直接构造调用与 `ChatControllerTest` 的测试配置改为注入记录型持久化端口，避免 Spring 构造器选择歧义或遗留测试编译失败。

- [ ] **步骤 4：运行 Agent 定向回归验证绿灯**

运行：`mvn -pl dodo-agent-learn -Dtest=ManualReactAgentMemoryTest,ManualReactAgentTest,ChatControllerTest,ReactRunContextTest test`

预期：PASS；取消、并发、SSE 事件和 `boundedElastic` 断言不回归。

- [ ] **步骤 5：提交 ReAct 集成**

运行：`git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentMemoryTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java && git commit -m "feat: record manual react run metrics"`

### 任务 4：沉淀学习材料并执行全量验证

**文件：**
- 创建：`dodo-agent-learn/tutorials/stages/06-agent-run-trace-persistence.md`

- [ ] **步骤 1：编写阶段讲义**

讲义必须包含：`ConversationMemory` 与 `SuccessfulAgentRunPersistence` 的职责差异；一条成功记录的字段映射；`System.nanoTime` 的单调耗时语义；首次可观察事件定义；工具去重规则；先持久化再 `text` 的失败边界；禁止思维链；完整核心伪代码；单元和 H2 集成测试策略。

- [ ] **步骤 2：运行完整模块测试**

运行：`mvn -pl dodo-agent-learn test`

预期：所有测试套件通过，Failures: 0，Errors: 0。

- [ ] **步骤 3：执行静态与工作区检查**

运行：`git diff --check && git status --short`

预期：无空白错误；工作区只包含本阶段讲义和待提交实现文件。

- [ ] **步骤 4：提交学习阶段**

运行：`git add dodo-agent-learn/tutorials/stages/06-agent-run-trace-persistence.md && git commit -m "docs: explain agent run trace persistence"`

## 自检

- 规格覆盖度：任务 1 覆盖不可变数据与校验；任务 2 覆盖单行 MyBatis 写入、JSON 工具名及问答读取隔离；任务 3 覆盖耗时、工具收集、成功顺序和所有失败边界；任务 4 覆盖教学文档与全量验证。
- 占位符扫描：所有任务均包含精确路径、测试目标、命令与预期结果，没有待定行为。
- 类型一致性：`SuccessfulAgentRun`、`SuccessfulAgentRunPersistence`、`MyBatisSuccessfulAgentRunPersistence` 和 `executedToolNames` 在全部任务中使用同一名称和职责。
