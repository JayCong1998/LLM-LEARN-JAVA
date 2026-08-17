# 工具保护与最终回答流式输出实现计划

> 面向 AI 代理的工作者：必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框语法跟踪进度。

**目标：** 为每个工具增加熔断与会话级限流；保留 /chat/call 一次性回答，并新增页面使用的 /chat/stream 最终回答 Token 流式版本。

**架构：** 新增纯 Java 工具保护状态组件，组合为 CircuitBreaker → RateLimiter → 已有重试与超时链。共享协调器承载 ReAct 工具阶段；ManualReactCallAgent 与 ManualReactStreamAgent 分别承载完整最终回答与最终片段输出；FinalAnswerStreamPort 隔离 Spring AI。

**技术栈：** Java 21、Spring Boot、Spring AI、Reactor、WebFlux SSE、JUnit 5、AssertJ、原生 JavaScript。

---

## 文件结构

- 创建：dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionContext.java，传递 conversationId、工具名与调用编号。
- 创建：tool/ToolCircuitBreaker.java、tool/ToolRateLimiter.java、tool/CircuitBreakingToolExecutor.java、tool/RateLimitedToolExecutor.java。
- 创建：agent/FinalAnswerStreamPort.java、agent/SpringAiFinalAnswerStreamAdapter.java、agent/ManualReactRunCoordinator.java、agent/ManualReactCallAgent.java、agent/ManualReactStreamAgent.java。
- 修改：tool/ToolExecutionPort.java、tool/RetryingToolExecutor.java、agent/ManualReactAgent.java、web/ChatController.java、static/js/app.js。
- 创建：工具、流端口、call Agent、stream Agent 的对应测试，以及阶段 11、12 教程。

### 任务 1：建立工具执行上下文

**文件：** 创建 ToolExecutionContext.java；修改 ToolExecutionPort.java 与 ManualReactAgent.java；创建 ToolExecutionPortTest.java。

- [ ] **步骤 1：编写失败测试**

~~~java
ToolExecutionContext context = new ToolExecutionContext("conversation-1", "weather", "call-1");
ToolExecutionPort port = (toolName, arguments) -> "正常结果";
assertThat(port.execute(context, "{}")).isEqualTo("正常结果");
~~~

- [ ] **步骤 2：运行确认红灯**

运行：mvn -pl dodo-agent-learn -Dtest=ToolExecutionPortTest test

预期：FAIL，提示 ToolExecutionContext 或上下文执行重载不存在。

- [ ] **步骤 3：实现最小上下文边界**

创建 immutable record：

~~~java
public record ToolExecutionContext(String conversationId, String toolName, String toolCallId) { }
~~~

在 ToolExecutionPort 增加 execute(ToolExecutionContext, String) 与带 ToolRetryListener 的默认重载，均委托既有两参数方法。Agent 为真实工具调用创建上下文。所有有效 Java 代码写准确中文注释。

- [ ] **步骤 4：运行确认转绿并提交**

运行：mvn -pl dodo-agent-learn '-Dtest=ToolExecutionPortTest,ManualReactAgentTest' test

预期：PASS，既有 Lambda 和重试事件测试不变。

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionContext.java dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolExecutionPort.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/ToolExecutionPortTest.java
git commit -m "feat: pass conversation context to tool execution"
~~~

### 任务 2：实现每工具熔断

**文件：** 创建 ToolCircuitBreaker.java、CircuitBreakingToolExecutor.java、ToolCircuitBreakerTest.java、CircuitBreakingToolExecutorTest.java。

- [ ] **步骤 1：编写失败测试**

使用可控 LongSupplier，断言 weather 的 3 次最终超时或工具执行失败后返回 工具已熔断：weather，delegate 不再调用；calculator 独立；30 秒到期只允许一次半开探测；探测成功清零、失败重新熔断。

~~~java
assertThat(executor.execute(weatherContext, "{}")).isEqualTo("工具已熔断：weather");
assertThat(delegateCalls).hasValue(3);
~~~

- [ ] **步骤 2：运行确认红灯**

运行：mvn -pl dodo-agent-learn '-Dtest=ToolCircuitBreakerTest,CircuitBreakingToolExecutorTest' test

预期：FAIL，提示熔断器类型不存在。

- [ ] **步骤 3：实现状态机**

按工具名用 ConcurrentHashMap 保存 CLOSED、OPEN、HALF_OPEN。使用 System.nanoTime 单调时钟；连续 3 次精确最终超时或 工具执行失败： 前缀打开 30 秒。半开使用原子占位只允许一次探测。未知工具、参数错误、限流、取消和重复调用跳过不增加失败。所有并发状态转换写块注释。

- [ ] **步骤 4：运行确认转绿并提交**

运行：mvn -pl dodo-agent-learn '-Dtest=ToolCircuitBreakerTest,CircuitBreakingToolExecutorTest,RetryingToolExecutorTest' test

预期：PASS。

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolCircuitBreaker.java dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/CircuitBreakingToolExecutor.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/ToolCircuitBreakerTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/CircuitBreakingToolExecutorTest.java
git commit -m "feat: add per-tool circuit breaker"
~~~

### 任务 3：实现会话级限流与生产链

**文件：** 创建 ToolRateLimiter.java、RateLimitedToolExecutor.java、ToolRateLimiterTest.java；修改 RetryingToolExecutor.java。

- [ ] **步骤 1：编写失败测试**

断言同一 conversationId + toolName 在一分钟内第 11 次返回 工具调用过于频繁：weather；不同会话和不同工具独立；60 秒后恢复；限流拒绝不调用 delegate；熔断拒绝不消耗限流额度。

- [ ] **步骤 2：运行确认红灯**

运行：mvn -pl dodo-agent-learn -Dtest=ToolRateLimiterTest test

预期：FAIL，提示限流器不存在。

- [ ] **步骤 3：实现滑动窗口与链装配**

使用 conversationId + 换行符 + toolName 作为键保存单调时间戳队列，剔除超过 60 秒的项，只允许前 10 次。生产 Bean 固定组装为 CircuitBreaking → RateLimited → Retrying → Timed，使用具体构造器注入与 @Primary，避免 ToolExecutionPort 循环或歧义。

- [ ] **步骤 4：运行确认转绿并提交**

运行：mvn -pl dodo-agent-learn '-Dtest=ToolRateLimiterTest,CircuitBreakingToolExecutorTest,RetryingToolExecutorTest,DodoAgentLearnApplicationTest' test

预期：PASS，应用上下文正确装配唯一生产端口。

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/ToolRateLimiter.java dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/RateLimitedToolExecutor.java dodo-agent-learn/src/main/java/com/jaycong/dodo/tool/RetryingToolExecutor.java dodo-agent-learn/src/test/java/com/jaycong/dodo/tool/ToolRateLimiterTest.java
git commit -m "feat: rate limit tool calls by conversation"
~~~

### 任务 4：定义最终回答流端口

**文件：** 创建 FinalAnswerStreamPort.java、SpringAiFinalAnswerStreamAdapter.java、FinalAnswerStreamPortTest.java、SpringAiFinalAnswerStreamAdapterTest.java。

- [ ] **步骤 1：编写失败测试**

~~~java
FinalAnswerStreamPort port = messages -> Flux.just("第", "一段");
StepVerifier.create(port.stream(List.of(new UserMessage("问题"))))
        .expectNext("第", "一段")
        .verifyComplete();
~~~

模拟 ChatModel，断言适配器使用完整 List<Message>、空 ToolCallingChatOptions 和 ChatClient.stream() 返回文本片段。

- [ ] **步骤 2：运行确认红灯**

运行：mvn -pl dodo-agent-learn '-Dtest=FinalAnswerStreamPortTest,SpringAiFinalAnswerStreamAdapterTest' test

预期：FAIL，提示最终回答流端口不存在。

- [ ] **步骤 3：实现端口与适配器**

~~~java
@FunctionalInterface
public interface FinalAnswerStreamPort {
    Flux<String> stream(List<Message> messages);
}
~~~

适配器以完整预算快照设置 messages，关闭工具和内部工具执行，过滤 null/空片段；适配器不聚合、不持久化。

- [ ] **步骤 4：运行确认转绿并提交**

运行：mvn -pl dodo-agent-learn '-Dtest=FinalAnswerStreamPortTest,SpringAiFinalAnswerStreamAdapterTest,SpringAiReactModelAdapterTest' test

预期：PASS。

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/FinalAnswerStreamPort.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/SpringAiFinalAnswerStreamAdapter.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/FinalAnswerStreamPortTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/SpringAiFinalAnswerStreamAdapterTest.java
git commit -m "feat: add final answer streaming port"
~~~

### 任务 5：拆分 call 与 stream Agent

**文件：** 创建 ManualReactRunCoordinator.java、ManualReactCallAgent.java、ManualReactStreamAgent.java、ManualReactCallAgentTest.java、ManualReactStreamAgentTest.java；修改 ManualReactAgent.java。

- [ ] **步骤 1：编写失败行为测试**

call 测试断言只产生一个完整 text。stream 测试以 Flux.just("第", "二", "段") 断言三条 text 后 complete，成功记忆只写一次完整 第一段。另写工具后流、空流、模型流异常、取消和持久化失败后 error → complete。

- [ ] **步骤 2：运行确认红灯**

运行：mvn -pl dodo-agent-learn '-Dtest=ManualReactCallAgentTest,ManualReactStreamAgentTest' test

预期：FAIL，提示新 Agent 或共享协调器不存在。

- [ ] **步骤 3：实现 call Agent 与共享协调器**

协调器复用系统提示、历史、预算、工具循环、取消、并发、错误和成功持久化。call Agent 使用 ReactModelPort 并保持一个 text；旧 ManualReactAgent 删除或降为非 Bean 兼容包装，生产只能装配两个明确 Agent。

- [ ] **步骤 4：实现 stream Agent**

流式 Agent 在最终阶段订阅 FinalAnswerStreamPort，每个非空片段立即输出 text 并追加 StringBuilder。流正常完成且完整文本非空才持久化；空流、异常、取消不持久化；持久化异常在已发文本后输出 运行轨迹保存失败：… 与 complete。

- [ ] **步骤 5：运行确认转绿并提交**

运行：mvn -pl dodo-agent-learn '-Dtest=ManualReactCallAgentTest,ManualReactStreamAgentTest,ManualReactAgentMemoryTest,ManualReactAgentRunTraceTest' test

预期：PASS。

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactRunCoordinator.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactCallAgent.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactStreamAgent.java dodo-agent-learn/src/main/java/com/jaycong/dodo/agent/ManualReactAgent.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactCallAgentTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/agent/ManualReactStreamAgentTest.java
git commit -m "feat: split call and stream react agents"
~~~

### 任务 6：拆分 HTTP 路由并切换页面

**文件：** 修改 web/ChatController.java、static/js/app.js、ChatControllerTest.java、LearningConsoleContractTest.java。

- [ ] **步骤 1：编写失败 Web 测试**

断言 GET /api/agent/chat/call 调用 call Agent；GET /api/agent/chat/stream 调用 stream Agent；页面包含 /api/agent/chat/stream 且不包含 /api/agent/chat/call。

- [ ] **步骤 2：运行确认红灯**

运行：mvn -pl dodo-agent-learn '-Dtest=ChatControllerTest,LearningConsoleContractTest' test

预期：FAIL，提示 call 路由或双 Agent 注入不存在。

- [ ] **步骤 3：实现路由与页面目标**

Controller 显式注入两个 Agent，保留参数校验和停止接口。页面只切换到 stream 地址，保留 AbortController、toolCallId 和 textContent，不增加模式选择。

- [ ] **步骤 4：运行确认转绿并提交**

运行：mvn -pl dodo-agent-learn '-Dtest=ChatControllerTest,LearningConsoleContractTest,DodoAgentLearnApplicationTest' test

预期：PASS。

~~~bash
git add dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ChatController.java dodo-agent-learn/src/main/resources/static/js/app.js dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ChatControllerTest.java dodo-agent-learn/src/test/java/com/jaycong/dodo/web/LearningConsoleContractTest.java
git commit -m "feat: expose call and stream chat routes"
~~~

### 任务 7：教程与完整验证

**文件：** 创建 tutorials/stages/11-tool-circuit-breaker-and-rate-limit.md 与 tutorials/stages/12-final-answer-token-streaming.md。

- [ ] **步骤 1：编写教程**

阶段 11 解释 3 次失败、30 秒熔断、半开单探测、每会话每工具 10 次/分钟和不计失败的结果。阶段 12 解释 call/stream 对照、共享工具循环、片段、取消、持久化失败可见错误与不展示思维链。

- [ ] **步骤 2：运行完整验证并提交**

运行：mvn -pl dodo-agent-learn test；git diff --check；git status --short

预期：BUILD SUCCESS，Failures: 0，Errors: 0，差异无空白错误。

~~~bash
git add dodo-agent-learn/tutorials/stages/11-tool-circuit-breaker-and-rate-limit.md dodo-agent-learn/tutorials/stages/12-final-answer-token-streaming.md
git commit -m "docs: explain tool protection and answer streaming"
git status --short
~~~

预期：工作区干净。

## 计划自检

- 规格覆盖度：任务 1 至 3 覆盖工具上下文、熔断、半开与会话限流；任务 4 至 6 覆盖双 Agent、最终流、路由和页面；任务 7 覆盖教程与全量验证。
- 占位符扫描：无待定事项；所有新类型、状态机、常量和验证命令均在对应任务定义。
- 类型一致性：工具保护统一使用 ToolExecutionContext；最终流统一使用 FinalAnswerStreamPort.stream(List<Message>)；路由固定为 /api/agent/chat/call 和 /api/agent/chat/stream。
