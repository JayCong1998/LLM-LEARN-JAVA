# Manual ReAct Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** 在 `dodo-agent-learn` 中实现一个可观察、可取消、带确定性本地工具的手写 ReAct Agent，并通过 SSE 和学习控制台展示工具调用过程。

**Architecture:** 保留阶段一的流式 Agent 作为对照，新增模型决策端口、Spring AI 适配器、工具注册表、运行上下文和手写 ReAct 状态机。状态机显式维护 `AssistantMessage -> ToolResponseMessage -> 下一轮模型决策`，控制器仍只负责 HTTP/SSE 协议转换，前端只展示工具开始、工具结束和最终答案，不暴露模型内部思维链。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AI 1.1、Spring WebFlux、Project Reactor、JUnit 5、AssertJ、Mockito、原生 HTML/CSS/JavaScript。

---

## Task 1：扩展 Agent SSE 事件协议

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java`
- Modify: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java`

**Step 1：先写失败测试**

新增测试，断言 `toolStart(toolName, toolCallId, arguments)` 生成 `tool_start` 事件，`toolEnd(toolName, toolCallId, content)` 生成 `tool_end` 事件；同时断言原有 `text/error/complete` 工厂的新增字段为 `null`。

**Step 2：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=AgentStreamEventTest test`

Expected: 编译失败，提示 `toolStart`、`toolEnd` 或新增访问器不存在。

**Step 3：实现最小协议扩展**

将记录类型扩展为：

```java
public record AgentStreamEvent(
        String type,
        String content,
        String toolName,
        String toolCallId,
        String arguments) {
}
```

保留 `text/error/complete`，并新增 `toolStart/toolEnd` 工厂。所有新增及修改的有效代码行按 `AGENTS.md` 添加中文注释，导入和注解除外。

**Step 4：运行测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=AgentStreamEventTest test`

Expected: `BUILD SUCCESS`。

**Step 5：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/AgentStreamEvent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/AgentStreamEventTest.java
git commit -m "feat: add tool lifecycle stream events"
```

## Task 2：实现两个确定性本地工具

**Files:**
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/WeatherTool.java`
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/CalculatorTool.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/WeatherToolTest.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/CalculatorToolTest.java`

**Step 1：先写天气工具失败测试**

覆盖北京、上海、深圳三个固定结果，城市名首尾空格，以及未知城市返回稳定的“暂无数据”观察结果。

**Step 2：运行天气测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=WeatherToolTest test`

Expected: 编译失败，因为 `WeatherTool` 尚不存在。

**Step 3：实现天气工具**

用 Spring AI `@Tool` 暴露方法，方法接收城市字符串并返回确定性中文文本，不访问网络。固定数据放在不可变 `Map` 中。

**Step 4：运行天气测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=WeatherToolTest test`

Expected: `BUILD SUCCESS`。

**Step 5：先写计算器失败测试**

覆盖 `ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE`，验证小数精度、除零、未知运算符和空输入的稳定错误信息。

**Step 6：运行计算器测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=CalculatorToolTest test`

Expected: 编译失败，因为 `CalculatorTool` 尚不存在。

**Step 7：实现计算器工具**

定义 `CalculationRequest(left, right, operator)` 输入记录，使用 `BigDecimal` 运算；除法采用显式精度和舍入规则，所有业务错误返回可交给模型理解的文本，不向 ReAct 循环抛出业务异常。

**Step 8：运行工具测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=WeatherToolTest,CalculatorToolTest test`

Expected: `BUILD SUCCESS`。

**Step 9：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool dodo-agent-learn/src/test/java/com/jaycong/dodo/tool
git commit -m "feat: add deterministic learning tools"
```

## Task 3：实现工具注册表与统一执行边界

**Files:**
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/AgentToolRegistry.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/AgentToolRegistryTest.java`

**Step 1：先写失败测试**

使用测试 `ToolCallback` 验证：注册表可导出全部回调供模型声明工具；可按名称执行 JSON 参数；未知工具、回调异常、空返回值均转换成稳定 Observation 文本。

**Step 2：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=AgentToolRegistryTest test`

Expected: 编译失败，因为注册表尚不存在。

**Step 3：实现注册表**

构造器接收 `List<ToolCallback>` 并建立不可变名称索引；提供 `ToolCallback[] callbacks()` 和 `String execute(String toolName, String arguments)`。名称来自 `toolCallback.getToolDefinition().name()`，执行使用 `toolCallback.call(arguments)`。

**Step 4：运行测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=AgentToolRegistryTest test`

Expected: `BUILD SUCCESS`。

**Step 5：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/AgentToolRegistry.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/AgentToolRegistryTest.java
git commit -m "feat: add agent tool registry"
```

## Task 4：建立 ReAct 模型端口和 Spring AI 适配器

**Files:**
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactModelPort.java`
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiReactModelAdapter.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactModelPortTest.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/SpringAiReactModelAdapterTest.java`

**Step 1：先写端口契约测试**

固定端口方法签名：

```java
AssistantMessage decide(List<Message> messages, boolean toolsEnabled);
```

测试用假实现记录消息快照和 `toolsEnabled`，证明 Agent 测试无需真实 API key。

**Step 2：运行端口测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ReactModelPortTest test`

Expected: 编译失败，因为端口尚不存在。

**Step 3：实现端口并确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ReactModelPortTest test`

Expected: `BUILD SUCCESS`。

**Step 4：先写适配器失败测试**

验证启用工具时构造 `ToolCallingChatOptions`，包含注册表全部回调且 `internalToolExecutionEnabled(false)`；禁用工具时不把工具回调交给模型；最终返回 `AssistantMessage`。

**Step 5：运行适配器测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=SpringAiReactModelAdapterTest test`

Expected: 编译失败，因为适配器尚不存在。

**Step 6：实现 Spring AI 适配器**

使用 `ChatClient` 同步 `call()` 完成一次决策。工具开启时显式设置：

```java
ToolCallingChatOptions.builder()
        .toolCallbacks(toolRegistry.callbacks())
        .internalToolExecutionEnabled(false)
        .build();
```

工具关闭时构造不含回调的选项，使超限后的强制收尾决策无法继续请求工具。

**Step 7：运行适配器测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=SpringAiReactModelAdapterTest test`

Expected: `BUILD SUCCESS`。

**Step 8：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactModelPort.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiReactModelAdapter.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactModelPortTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/SpringAiReactModelAdapterTest.java
git commit -m "feat: add react model decision boundary"
```

## Task 5：实现单次运行上下文

**Files:**
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactRunContextTest.java`

**Step 1：先写失败测试**

验证上下文维护有序消息、当前轮次、最大四轮、已执行工具签名、`cancelled` 和 `finished` 原子状态；重复签名判定使用 `toolName + trimmed raw arguments`。

**Step 2：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ReactRunContextTest test`

Expected: 编译失败，因为运行上下文尚不存在。

**Step 3：实现运行上下文**

上下文只保存单次请求状态，不注册为 Spring 单例。消息列表只通过方法追加和快照读取；状态转换使用 `AtomicBoolean.compareAndSet` 防止取消、完成与异常路径重复发终止事件。

**Step 4：运行测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ReactRunContextTest test`

Expected: `BUILD SUCCESS`。

**Step 5：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ReactRunContext.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ReactRunContextTest.java
git commit -m "feat: add react run context"
```

## Task 6：实现手写 ReAct 主循环的正常路径

**Files:**
- Create: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- Create: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`

**Step 1：先写直接回答失败测试**

脚本模型端口返回无工具调用的 `AssistantMessage`，断言输出严格为一个完整 `text` 事件和一个 `complete` 事件，任务注册随后被释放。

**Step 2：先写单工具失败测试**

脚本模型先返回天气工具调用，再返回最终回答。断言：模型第二轮消息依次包含原始用户消息、带工具调用的 `AssistantMessage`、对应 `ToolResponseMessage`；事件顺序为 `tool_start -> tool_end -> text -> complete`。

**Step 3：先写多工具失败测试**

一轮返回两个工具调用，断言工具按模型给出的顺序串行执行，两个 `ToolResponse` 保持相同顺序后再进入下一次模型决策。

**Step 4：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ManualReactAgentTest test`

Expected: 编译失败，因为 Agent 尚不存在。

**Step 5：实现正常路径主循环**

核心循环伪代码对应实现如下：

```text
创建 SystemMessage + UserMessage
在 boundedElastic 执行同步模型决策
while 未取消且未完成:
    assistant = model.decide(messages, toolsEnabled=true)
    追加 assistant
    if assistant 没有工具调用:
        发 text(完整最终答案)
        发 complete
        结束
    for toolCall in assistant.toolCalls 按顺序:
        发 tool_start
        observation = registry.execute(name, arguments)
        发 tool_end
        收集 ToolResponse
    追加 ToolResponseMessage
finally 从任务注册表释放 conversationId
```

用 `Flux.create` 输出事件，整个同步决策循环通过 `subscribeOn(Schedulers.boundedElastic())` 隔离，避免阻塞 WebFlux 事件线程。

**Step 6：运行正常路径测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ManualReactAgentTest test`

Expected: 直接回答、单工具、多工具测试均通过。

**Step 7：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java
git commit -m "feat: implement manual react loop"
```

## Task 7：补齐 ReAct 防护、异常与取消语义

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java`
- Modify: `dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java`

**Step 1：先写失败测试**

增加以下场景：

- 未知工具、非法 JSON、工具异常都成为 `tool_end` Observation，循环继续。
- 重复 `toolName + trimmed arguments` 仍发 `tool_start/tool_end`，但不再次执行真实工具。
- 连续四轮仍请求工具时，追加“基于已有观察立即总结”的用户消息，并以 `toolsEnabled=false` 发起一次收尾决策。
- 收尾回答为空，或模型抛出异常时，输出一个 `error` 后输出 `complete`。
- 同一 `conversationId` 并发请求沿用任务注册表现有冲突语义。
- 取消后只输出稳定取消错误和 `complete`；阻塞模型稍后返回的结果被丢弃；任务最终释放。
- 客户端取消订阅时触发最佳努力取消和资源释放。

**Step 2：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ManualReactAgentTest test`

Expected: 新增防护场景至少一项失败。

**Step 3：实现状态防护**

每次模型返回、工具执行前后都检查 `cancelled/finished`。终止路径统一通过原子完成方法发出至多一次终止序列。重复调用返回明确 Observation；最大轮次之后关闭工具能力，只允许模型生成最终文本。模型 API 异常和空最终文本才进入 Agent 级错误事件。

**Step 4：运行测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ManualReactAgentTest test`

Expected: `BUILD SUCCESS`。

**Step 5：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactAgentTest.java
git commit -m "feat: guard react loop lifecycle"
```

## Task 8：将 HTTP 入口切换到手写 ReAct Agent

**Files:**
- Modify: `dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java`
- Modify: `dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java`
- Modify: `dodo-agent-learn/src/test/java/com/jaycong/dodo/DodoAgentLearnApplicationTest.java`

**Step 1：先写失败测试**

把控制器测试依赖切换为 `ManualReactAgent`，验证 `tool_start/tool_end` 的 SSE `event` 字段和 JSON `data` 字段均完整；保留空参数 400、停止接口和 text/error/complete 映射测试。应用上下文测试用 mock 模型端口，确保无需 API key 启动测试上下文。

**Step 2：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ChatControllerTest,DodoAgentLearnApplicationTest test`

Expected: 控制器构造器类型或上下文装配失败。

**Step 3：切换控制器依赖**

只将默认 `agent` 字段和构造器类型替换为 `ManualReactAgent`。URL、请求参数、停止接口和 SSE 映射保持不变。

**Step 4：运行测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=ChatControllerTest,DodoAgentLearnApplicationTest test`

Expected: `BUILD SUCCESS`。

**Step 5：提交**

```bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/DodoAgentLearnApplicationTest.java
git commit -m "feat: expose manual react agent over sse"
```

## Task 9：在学习控制台展示工具生命周期

**Files:**
- Modify: `dodo-agent-learn/src/main/resources/static/index.html`
- Modify: `dodo-agent-learn/src/main/resources/static/js/app.js`
- Modify: `dodo-agent-learn/src/main/resources/static/css/style.css`
- Modify: `dodo-agent-learn/src/test/java/com/jaycong/dodo/web/LearningConsoleContractTest.java`

**Step 1：先写失败契约测试**

断言页面包含工具轨迹容器，脚本显式处理 `tool_start` 和 `tool_end`，并按 `toolCallId` 更新同一张工具卡；断言界面文案不包含“思维链”“Chain of Thought”等内部推理展示。

**Step 2：运行测试确认红灯**

Run: `mvn -pl dodo-agent-learn -Dtest=LearningConsoleContractTest test`

Expected: 缺少阶段二 DOM 标识或事件分支。

**Step 3：实现前端工具卡片**

新增工具轨迹区域。收到 `tool_start` 时创建运行中卡片，显示工具名和格式化参数；收到相同 `toolCallId` 的 `tool_end` 时更新为完成状态并显示 Observation。最终 `text` 仍进入答案区域，`complete/error/stop` 沿用现有行为。

**Step 4：运行契约测试确认绿灯**

Run: `mvn -pl dodo-agent-learn -Dtest=LearningConsoleContractTest test`

Expected: `BUILD SUCCESS`。

**Step 5：提交**

```bash
git add dodo-agent-learn/src/main/resources/static/index.html dodo-agent-learn/src/main/resources/static/js/app.js dodo-agent-learn/src/main/resources/static/css/style.css dodo-agent-learn/src/test/java/com/jaycong/dodo/web/LearningConsoleContractTest.java
git commit -m "feat: visualize react tool lifecycle"
```

## Task 10：全量验证并产出第二阶段独立文档

**Files:**
- Create: `dodo-agent-learn/tutorials/stages/02-manual-react-agent.md`
- Modify when necessary: `dodo-agent-learn/README.md`

**Step 1：运行全量测试**

Run: `mvn -pl dodo-agent-learn clean test`

Expected: `BUILD SUCCESS`，全部阶段一和阶段二测试通过。

若 Windows 报 `target/*.jar` 被占用，先通过只读进程查询定位该模块的 Java 进程，再停止明确属于本模块的进程，禁止模糊结束全部 Java 进程。

**Step 2：检查逐行中文注释规则**

审查本阶段所有新增或修改的 Java、JavaScript、HTML、CSS 有效代码行。导入和注解可以不注释，其余代码必须有对应中文说明；ReAct 循环、并发、取消、工具异常和资源释放必须有块级中文注释。

**Step 3：编写第二阶段文档**

文档必须脱离当前代码也能完整复习，至少包含：

- 阶段目标、非目标和验收条件。
- 手写 ReAct 的核心原理和消息协议。
- 全部核心类职责、依赖方向和运行时序。
- 工具定义、注册、模型声明、人工执行和 Observation 回填方式。
- SSE 工具事件协议与前端卡片状态映射。
- 四轮上限、重复调用、工具错误、模型错误和取消语义。
- 完整核心伪代码，包括正常路径、防护路径和终止路径。
- 测试策略、运行命令和可复现实验问题。
- 与阶段一的区别以及下一阶段可扩展点。

**Step 4：运行文档与敏感信息检查**

Run: `rg -n "TODO|TBD|sk-[A-Za-z0-9_-]+|api-key:\s*[^$]" dodo-agent-learn/tutorials/stages/02-manual-react-agent.md dodo-agent-learn/src`

Expected: 无真实密钥、无未完成占位符；示例配置只引用环境变量。

**Step 5：再次运行全量测试**

Run: `mvn -pl dodo-agent-learn test`

Expected: `BUILD SUCCESS`。

**Step 6：提交阶段文档**

```bash
git add dodo-agent-learn/tutorials/stages/02-manual-react-agent.md dodo-agent-learn/README.md
git commit -m "docs: explain manual react agent stage"
```

## 完成标准

- 用户提问可由模型直接回答，或由模型选择天气/计算器工具后再总结。
- Spring AI 自动工具执行明确关闭，工具调用完全由本项目代码掌控。
- SSE 顺序可观察，前端不展示内部思维链。
- 工具错误不会击穿整个 Agent；模型错误会稳定终止。
- 最大轮次、重复调用、同会话并发、主动停止和客户端断开均有测试。
- `mvn -pl dodo-agent-learn clean test` 通过。
- 第二阶段文档包含可长期保存的完整伪代码和实现说明。
