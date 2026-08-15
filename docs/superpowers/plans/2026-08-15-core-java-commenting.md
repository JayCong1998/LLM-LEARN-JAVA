# Core Java Commenting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `dodo-agent-learn` 的七个核心 Java 文件补充逐行中文注释和关键原理说明，同时保持编译产物行为不变。

**Architecture:** 按依赖方向分四批改造：启动与协议、外部适配、任务并发、Agent 生命周期。每批只添加注释并立即编译；最终通过去注释语义对比、15 个测试和可执行 JAR 打包证明没有修改业务代码。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring WebFlux、Spring AI、Project Reactor、Maven、JUnit 5

---

### Task 1: 建立基线并注释启动与协议边界

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/DodoAgentLearnApplication.java`
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java`
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ChatStreamPort.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/DodoAgentLearnApplicationTest.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ChatStreamPortTest.java`

- [ ] **Step 1: 运行未改造代码的测试基线**

Run: `mvn -pl dodo-agent-learn test`

Expected: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 2: 为启动类增加逐行中文注释**

添加以下类级说明，并为 package、import、注解、类、main 方法、启动调用和括号逐行添加中文说明：

```java
/**
 * 学习项目的 Spring Boot 启动入口。
 * SpringApplication.run 会创建应用上下文、执行自动配置并启动内嵌 WebFlux 服务。
 */
```

保持 `main(String[] args)` 和 `SpringApplication.run(DodoAgentLearnApplication.class, args)` 原样不变。

- [ ] **Step 3: 为稳定事件协议增加逐行中文注释**

保留 `text`、`error`、`complete` 三个工厂方法和原有字符串值。类级注释采用：

```java
/**
 * Agent 向 HTTP 层输出的稳定事件协议。
 * 记录类型保持事件种类和负载结构统一，使 Agent 不依赖 SSE 等具体传输方式。
 */
```

逐行解释记录组件、静态工厂方法、事件类型值和完成事件空内容的原因。

- [ ] **Step 4: 为模型端口增加逐行中文注释**

保留函数式接口和 `Flux<String> stream(String message)` 签名。接口说明采用：

```java
/**
 * Agent 所依赖的最小模型流端口。
 * Agent 只消费文本片段，不感知 Spring AI、模型厂商或底层网络协议。
 */
```

- [ ] **Step 5: 编译并提交本批文件**

Run: `mvn -pl dodo-agent-learn -DskipTests compile`

Expected: `BUILD SUCCESS`.

Commit:

```powershell
git add -- dodo-agent-learn/src/main/java/com/jaycong/dodo/DodoAgentLearnApplication.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ChatStreamPort.java
git commit -m "docs: explain agent entrypoint and stream protocol"
```

### Task 2: 注释模型适配器和 HTTP 边界

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiChatStreamAdapter.java`
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/SpringAiChatStreamAdapterTest.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java`

- [ ] **Step 1: 注释 Spring AI 适配器**

逐行解释 `ChatModel`、`ChatClient`、构造注入和 `prompt → user → stream → content` 调用链。方法说明采用：

```java
/**
 * 把一条用户消息交给模型，并返回按照片段异步产生的文本流。
 * 此处不订阅 Flux，订阅和任务生命周期由上层 StreamingChatAgent 统一管理。
 */
```

- [ ] **Step 2: 注释 SSE Controller**

逐行解释 WebFlux 注解、构造注入、空白参数校验、Agent 事件到 `ServerSentEvent` 的映射以及停止接口。流接口说明采用：

```java
/**
 * 建立一次 SSE 对话流。
 * Controller 只负责 HTTP 边界，实际任务生命周期由 StreamingChatAgent 管理。
 */
```

保持路径、参数名称、HTTP 状态和响应类型不变。

- [ ] **Step 3: 编译并提交本批文件**

Run: `mvn -pl dodo-agent-learn -DskipTests compile`

Expected: `BUILD SUCCESS`.

Commit:

```powershell
git add -- dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiChatStreamAdapter.java dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java
git commit -m "docs: explain model adapter and SSE boundary"
```

### Task 3: 注释并发任务注册与取消语义

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/task/InMemoryTaskRegistry.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/task/InMemoryTaskRegistryTest.java`

- [ ] **Step 1: 注释注册表公共操作**

逐行解释 `ConcurrentMap`、`putIfAbsent` 原子注册、查询、订阅绑定、取消和正常完成。类级说明采用：

```java
/**
 * 保存当前进程内正在运行的 Agent 任务。
 * 会话编号既是并发互斥键，也是停止接口定位模型订阅的索引。
 */
```

- [ ] **Step 2: 注释 TaskEntry 的竞争处理**

逐行解释 `onCancel`、`subscription`、`closed` 和三个同步方法，并加入：

```java
/*
 * 注册任务与取得模型 Disposable 不是同一个原子步骤。
 * 如果取消先发生，attach 会看到 closed=true，并立即 dispose 迟到的订阅，避免模型继续运行。
 */
```

保持同步范围、字段类型和回调调用顺序不变。

- [ ] **Step 3: 运行注册表测试、编译并提交**

Run:

```powershell
mvn -pl dodo-agent-learn -Dtest=InMemoryTaskRegistryTest test
mvn -pl dodo-agent-learn -DskipTests compile
```

Expected: `Tests run: 3, Failures: 0, Errors: 0` and both commands report `BUILD SUCCESS`.

Commit:

```powershell
git add -- dodo-agent-learn/src/main/java/com/jaycong/dodo/task/InMemoryTaskRegistry.java
git commit -m "docs: explain task registry cancellation semantics"
```

### Task 4: 注释流式 Agent 生命周期

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/StreamingChatAgent.java`
- Test: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/StreamingChatAgentTest.java`

- [ ] **Step 1: 注释每次订阅的独立运行环境**

逐行解释 `Flux.defer`、单订阅 Sink、背压缓冲和 `AtomicBoolean`。方法说明采用：

```java
/**
 * 为一轮对话创建可取消的模型文本流。
 * defer 保证每个 HTTP 订阅都拥有独立的 Sink、终止标记和任务注册过程。
 */
```

- [ ] **Step 2: 注释注册、订阅和取消顺序**

加入以下不变量说明，并逐行解释重复会话事件、文本转发、错误/完成回调和 `tasks.attach`：

```java
/*
 * 先注册会话，再订阅模型，才能阻止同一会话重复运行。
 * 订阅产生 Disposable 后立刻绑定到注册表，使停止接口能够释放上游模型请求。
 */
```

- [ ] **Step 3: 注释终止竞争和统一清理**

逐行解释 `doFinally`、`SignalType.CANCEL`、成功完成和异常完成。说明 `compareAndSet(false, true)` 保证多个终止信号中只有一个分支发送最终事件。

保持事件顺序不变：异常时 `error → complete → Flux complete`，成功时 `complete → Flux complete`。

- [ ] **Step 4: 运行 Agent 测试、编译并提交**

Run:

```powershell
mvn -pl dodo-agent-learn -Dtest=StreamingChatAgentTest test
mvn -pl dodo-agent-learn -DskipTests compile
```

Expected: `Tests run: 4, Failures: 0, Errors: 0` and both commands report `BUILD SUCCESS`.

Commit:

```powershell
git add -- dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/StreamingChatAgent.java
git commit -m "docs: explain streaming agent lifecycle"
```

### Task 5: 审计逐行覆盖并验证行为不变

**Files:**
- Verify: all seven Java files listed in Tasks 1-4

- [ ] **Step 1: 人工审计逐行注释覆盖**

逐文件检查每一行非空有效代码都有中文注释；重点检查 `package`、`import`、注解、方法参数续行、链式调用续行、分支括号和闭合括号。

- [ ] **Step 2: 对比去注释后的代码语义**

从改造前提交读取七个文件，分别去掉 `/* ... */`、`// ...` 和空白后，与工作树版本做序列比较。

Expected: 七个文件的非注释 Token 顺序全部一致；发现差异时恢复原代码，不进行额外重构。

- [ ] **Step 3: 运行全量测试**

Run: `mvn -pl dodo-agent-learn test`

Expected: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 4: 构建可执行 JAR**

Run: `mvn -pl dodo-agent-learn package -DskipTests`

Expected: `BUILD SUCCESS` and `dodo-agent-learn/target/dodo-agent-learn-1.0-SNAPSHOT.jar` exists.

- [ ] **Step 5: 检查提交和用户工作树**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; previously staged Python/PPT/frontend reference resources and user configuration changes remain preserved and are not included in the annotation commits.

