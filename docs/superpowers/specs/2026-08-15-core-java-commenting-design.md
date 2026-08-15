# 核心 Java 代码注释改造设计

## 目标

为 `dodo-agent-learn` 第一阶段的七个核心 Java 文件补充完整的中文学习注释，使每一行有效代码都有用途说明，并详细解释 Agent 的关键设计与运行原理。

本次改造只增加注释，不修改类型、字段、方法签名、控制流、事件协议或运行行为。

## 文件范围

- `DodoAgentLearnApplication.java`
- `AgentStreamEvent.java`
- `ChatStreamPort.java`
- `SpringAiChatStreamAdapter.java`
- `StreamingChatAgent.java`
- `InMemoryTaskRegistry.java`
- `ChatController.java`

测试、前端、配置文件、PPT/Python 资源和用户已暂存的其他参考文件不在本次范围内。

## 注释形式

采用混合注释方式：

- 类、接口、记录类型和方法使用中文 Javadoc 或上方注释，解释职责、输入输出和设计原因。
- 重要流程使用块级注释，解释执行步骤、状态变化、边界条件和失败处理。
- `package`、`import`、注解、简单字段、简单调用和闭合括号等代码使用上方注释或行尾注释，确保每行有效代码都有对应说明。
- 空行不添加注释。
- 注释强调代码在整体 Agent 系统中的意义，避免只复述 Java 语法。

## 重点讲解内容

### 启动和模型边界

- Spring Boot 自动配置与应用启动入口。
- `ChatStreamPort` 如何通过依赖倒置隔离 Agent 和具体模型 SDK。
- `SpringAiChatStreamAdapter` 如何把 Spring AI 的响应转换为纯文本 `Flux`。

### 流式 Agent 生命周期

- `Flux.defer` 为什么为每次订阅创建独立任务状态。
- 单订阅 Sink 和背压缓冲在浏览器流式响应中的作用。
- `AtomicBoolean` 如何保证成功、异常和取消只能有一个终止分支获胜。
- 为什么必须先注册任务，再订阅模型流并绑定 `Disposable`。
- 文本、错误和完成事件如何组成稳定的前端协议。
- `doFinally` 如何区分客户端取消与正常终止，并触发对应清理。

### 并发和任务取消

- `ConcurrentHashMap.putIfAbsent` 如何阻止同一会话并发执行。
- 注册、模型订阅和外部取消之间可能出现的竞争窗口。
- `TaskEntry.attach` 为什么在任务已经关闭时立即释放迟到的订阅。
- `synchronized` 如何保护 `subscription` 与 `closed` 的组合状态。
- 主动取消与正常完成在回调行为上的差异。

### HTTP 与 SSE 边界

- Controller 为什么只处理参数、HTTP 状态和 SSE 封装。
- 参数校验为什么在启动 Agent 任务前完成。
- Agent 事件类型如何映射为 SSE `event` 字段。
- 停止接口如何通过会话编号定位运行任务。

## 行为保护

改造过程中不得重命名、重排或重构现有实现，不引入新依赖，也不修改测试断言。

完成后执行以下验证：

```powershell
mvn -pl dodo-agent-learn test
mvn -pl dodo-agent-learn package -DskipTests
```

验收标准为 15 个测试全部通过、可执行 JAR 构建成功，并通过 Git diff 确认七个目标文件只增加或调整注释，没有业务表达式和控制流变化。
