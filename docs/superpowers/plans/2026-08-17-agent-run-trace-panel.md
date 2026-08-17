# Agent 运行轨迹查询与前端面板实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 查询当前会话最近十条成功运行并安全展示工具、耗时、类型和时间。

**架构：** 新增 `AgentRunTraceQuery` 端口及 MyBatis 查询适配器；控制器将阻塞查询转移到 `boundedElastic`；原生页面独立刷新运行轨迹，不影响会话记忆和 SSE。

**技术栈：** Java 21、Spring WebFlux、MyBatis-Plus、H2、JUnit 5、原生 JavaScript。

---

### 任务 1：定义安全运行轨迹查询端口

**文件：**
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/AgentRunTrace.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/AgentRunTraceQuery.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/trace/MyBatisAgentRunTraceQuery.java`
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/trace/MyBatisAgentRunTraceQueryTest.java`

- [ ] 编写失败 H2 测试：为两个会话插入完整 `AiSessionEntity`，断言仅返回目标会话、按 `createTime/id` 倒序、最多十条，且 DTO 没有问题、回答、thinking 或工具参数字段。
- [ ] 运行：`mvn -pl dodo-agent-learn -Dtest=MyBatisAgentRunTraceQueryTest test`；预期因缺少查询类型失败。
- [ ] 实现 `AgentRunTrace(createdAt, tools, firstResponseTimeMillis, totalResponseTimeMillis, agentType)` 与 `getRecent(conversationId)`；Mapper 查询使用固定 `LIMIT 10`，只映射安全字段。
- [ ] 重跑同一测试；预期 PASS。

### 任务 2：暴露 WebFlux 只读查询 API

**文件：**
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/web/AgentRunTraceController.java`
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/AgentRunTraceControllerTest.java`

- [ ] 编写失败 WebFlux 测试：GET `/api/agent/conversations/c-1/runs` 返回 `runs` 数组；查询异常映射 500；记录端口线程名并断言含 `boundedElastic`。
- [ ] 运行：`mvn -pl dodo-agent-learn -Dtest=AgentRunTraceControllerTest test`；预期缺少控制器失败。
- [ ] 使用 `Mono.fromCallable(() -> new RunTraceResponse(query.getRecent(conversationId)))`、`subscribeOn(Schedulers.boundedElastic())` 和既有 500 响应语义实现控制器。
- [ ] 重跑同一测试；预期 PASS。

### 任务 3：添加原生前端轨迹面板

**文件：**
- 修改：`dodo-agent-learn/src/main/resources/static/index.html`
- 修改：`dodo-agent-learn/src/main/resources/static/js/app.js`
- 修改：`dodo-agent-learn/src/main/resources/static/css/style.css`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/LearningConsoleContractTest.java`

- [ ] 编写失败页面契约测试：断言轨迹容器、刷新按钮、`/runs` 路径、`textContent` 渲染、成功 complete 自动刷新与思维链禁用。
- [ ] 运行：`mvn -pl dodo-agent-learn -Dtest=LearningConsoleContractTest test`；预期缺少轨迹元素失败。
- [ ] 实现独立 `refreshRunTraces()`：调用当前会话 GET，创建工具、首次响应、总耗时、Agent 类型和时间卡片；工具 JSON 解析失败回退原文；请求失败仅更新轨迹状态。
- [ ] 重跑页面契约和相关 Web 测试；预期 PASS。

### 任务 4：记录阶段并验证

**文件：**
- 创建：`dodo-agent-learn/tutorials/stages/07-agent-run-trace-panel.md`

- [ ] 写明查询端口与会话记忆分离、十条倒序窗口、`boundedElastic`、前端安全渲染和禁止思维链。
- [ ] 运行：`mvn -pl dodo-agent-learn test` 与 `git diff --check`；预期全部通过。
- [ ] 仅提交本阶段代码、测试和讲义。
