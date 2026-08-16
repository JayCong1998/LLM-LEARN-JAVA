# dodo-agent-learn 新会话交接提示词

## 使用方法

在新电脑克隆 `LLM-LEARN-JAVA` 仓库后，打开本文件，将下面代码块中的全部内容复制为新 Codex 会话的第一条消息。

```text
你现在要继续协助我学习和开发一个 Java Agent 系统。请先恢复项目上下文，不要立即修改代码，也不要重新实现已经完成的功能。

## 一、项目与学习目标

Git 仓库：LLM-LEARN-JAVA
学习项目：dodo-agent-learn
参考项目（如果当前电脑存在）：相邻工作区中的 LLMentor/agent/dodo-agent

我的目标不是简单复制参考项目，而是深入理解 Agent 原理，并通过分阶段、可运行、可测试的方式，亲手搭建一个类似的 Agent 系统。你需要继续以教学方式带我逐步开发：先解释本阶段目标与原理，再按小步迭代实现；遇到关键架构、Reactor、SSE、工具调用、并发、取消、上下文和记忆逻辑时必须讲清楚为什么这样设计。

如果参考项目不在当前电脑，不要因此阻塞当前阶段。以仓库中已经沉淀的设计、计划、教程、代码、测试和 Git 历史为当前权威上下文。

## 二、开始工作前必须完整阅读

请按顺序完整阅读以下文件：

1. dodo-agent-learn/AGENTS.md
2. dodo-agent-learn/tutorials/stages/01-minimal-streaming-agent.md
3. dodo-agent-learn/tutorials/stages/02-manual-react-agent.md
4. docs/superpowers/specs/2026-08-16-conversation-memory-design.md
5. docs/superpowers/plans/2026-08-16-conversation-memory.md

如需理解早期设计过程，再阅读：

- docs/superpowers/specs/2026-08-15-minimal-streaming-agent-design.md
- docs/superpowers/specs/2026-08-16-manual-react-agent-design.md
- docs/superpowers/plans/2026-08-15-minimal-streaming-agent.md
- docs/superpowers/plans/2026-08-16-manual-react-agent.md

随后检查以下内容：

- git status --short
- git branch --show-current
- git log --oneline -20
- dodo-agent-learn/src/main/java 下的当前实现
- dodo-agent-learn/src/test/java 下的当前测试
- dodo-agent-learn/src/main/resources/static/index.html

先用中文向我总结你恢复出的项目架构、阶段进度、下一任务和工作区状态。在完成这些只读检查前不要修改文件。

## 三、已经完成的学习阶段

第一阶段已经完成：最小流式 Agent。

- 建立 Spring Boot、Spring WebFlux 和 Spring AI 基础工程。
- 使用 SSE 暴露流式接口。
- 建立 Agent 事件协议、任务注册、同会话互斥、停止与资源清理能力。
- 第一阶段的独立教程和核心伪代码已保存在 tutorials/stages/01-minimal-streaming-agent.md。

第二阶段已经完成：手写 ReAct Agent。

- 显式实现“模型决策 → 工具调用 → Observation 回填 → 再次决策”的循环。
- 模型调用使用同步 ReactModelPort，但整个阻塞循环运行在 boundedElastic，不能删除 subscribeOn，否则会在 reactor-http-nio 线程触发 blocking 异常。
- 外部 /stream 接口仍然是 SSE；内部同步模型决策与外部事件流并不矛盾。
- 支持 tool_start、tool_end、text、error、complete 事件。
- SSE 中的 event:tool_start 等字段由 ChatController 的 ServerSentEvent.event(event.type()) 生成，JSON data 由 AgentStreamEvent 序列化。
- 支持工具注册、顺序执行、工具响应回填、重复调用保护、最大决策轮次、强制收尾、取消和同会话并发保护。
- 第二阶段的独立教程和核心伪代码已保存在 tutorials/stages/02-manual-react-agent.md。

## 四、第三阶段当前状态

第三阶段目标：实现进程内、按 conversationId 隔离、最近 5 轮的跨 HTTP 请求会话记忆。

已经完成 Task 1～5：

1. ConversationTurn：不可变地表示一轮完整用户问题和最终助手回答，并拒绝空白内容。
2. ConversationMemory 与 InMemoryConversationMemory：线程安全、按会话隔离、最多保留最近 5 轮、返回不可变快照。
3. ManualReactAgent 在 boundedElastic 工作线程开始运行时读取一次历史快照，并按 System → 历史 User/Assistant → 当前 User 的顺序初始化上下文。
4. 只有正常成功回答才保存当前问答；模型错误、空答案、取消和并发拒绝不保存。记忆保存失败时发送 error + complete，不发送尚未保存的 text。
5. 已实现管理接口：
   GET /api/agent/conversations/{conversationId}/memory
   DELETE /api/agent/conversations/{conversationId}/memory

跨请求记忆只保存用户问题和最终回答，不保存 ToolCall、ToolResponse 或模型内部推理。工具消息只存在于单次 ReactRunContext 中。

DELETE 只清除已经保存的历史，不取消正在运行的 Agent，也不修改该运行已经加载的历史快照；若这个运行随后成功，其问答会成为清空后的第一轮。

尚未完成：

- Task 6：为 dodo-agent-learn/src/main/resources/static/index.html 增加会话记忆面板、刷新按钮和清空按钮，并先扩展页面契约测试。
- Task 7：完整测试、注释审计、敏感信息检查，并生成 dodo-agent-learn/tutorials/stages/03-conversation-memory.md。第三阶段教程必须包含目标、需求边界、核心类、实现原理、并发与终止语义、API、测试策略及可以脱离未来代码阅读的完整核心伪代码。

请从 Task 6 开始，不要重新开发 Task 1～5。开始前先核对实施计划中的 Task 6 和当前 index.html，继续使用 TDD：先写失败测试并实际确认失败，再写最小实现，随后运行定向回归测试。

## 五、必须遵守的开发规则

- 用户已经明确授权直接在 master 分支开发，不要新建 worktree 或功能分支。
- 不覆盖、不回滚、不提交范围外的用户修改；开始和提交前都检查 git status。
- dodo-agent-learn/AGENTS.md 是强制规则。
- 新增或修改的每一行有效代码都要有准确、具体、适合学习的中文注释。
- 普通 import 和注解可以不写注释。
- Agent 生命周期、SSE、并发控制、任务取消、工具调用、上下文、记忆、异常和资源释放必须有清晰块级注释，解释数据流、状态变化、边界条件和设计原因。
- 修改行为时同步修改注释，不能留下与实现不一致的旧注释。
- 新功能和缺陷修复使用 TDD：测试先行，观察正确红灯，再写最小实现并观察绿灯。
- 每个任务完成后运行定向测试和 git diff --check，使用范围精确的提交。
- 最终宣称完成前必须重新执行完整测试，不能复用较早测试结果。
- 不展示或要求模型输出内部思维链；只展示工具生命周期、最终回答和稳定错误。
- 不要擅自修改参考项目 LLMentor/agent/dodo-agent。

## 六、API Key 与本地配置安全

- 不要读取、输出、记录或提交真实 API Key。
- application.yml 只能引用环境变量或明显占位符。
- 如果新电脑启动时报 OpenAI API key must be set，先检查 application.yml 实际引用的环境变量名称，再让用户在本机设置该环境变量。
- 不要把密钥直接写入 Git、教程、测试、日志或聊天回复。
- 如果历史中曾出现过真实密钥，提醒用户在服务商后台轮换；不要复述旧密钥。

## 七、关键 Git 提交

可使用 git show <commit> 精确了解实现演进：

- d362d2f：第一阶段总结文档
- cae9e8b：第一阶段核心伪代码
- 5103ed1：工具生命周期事件
- b8417db：确定性工具
- 48aaed0：工具注册表
- b9f3829：模型端口与适配器
- 59aed63：单次运行上下文
- 3c16df4：手写 ReAct 循环
- 2421a36：循环与生命周期保护
- fe364a5：SSE、控制器和配置
- 547bc28：ReAct 工具生命周期前端展示
- 6e4dabd：第二阶段教程
- 657d5bc：第三阶段会话记忆设计
- 990a1a4：ConversationTurn
- f6b93d1：五轮内存窗口
- aa911da：Agent 加载跨请求历史
- e0b51e4：只保存成功问答
- 1d4697c：会话记忆查询和清空 API
- e25a093：统一 superpowers 文档目录
- 52bf479：将学习教程目录重命名为 tutorials

部分提交之间可能夹有用户对其他学习模块的提交，请以实际 git log 和文件状态为准，不要假设工作区一定干净。

## 八、恢复后的验证方式

在仓库根目录执行：

1. cd dodo-agent-learn
2. mvn test

如果 Windows 上 mvn clean 失败并提示 target JAR 被其他程序占用，先确认是否有旧 Java 进程或 IDE 运行配置仍在启动该 JAR；不要盲目删除或结束无关进程。普通 mvn test 不需要先 clean。

开始 Task 6 前，请先向我报告：

1. 当前分支与 git status。
2. 你读取到的第一、第二、第三阶段状态。
3. ManualReactAgent、ConversationMemory、InMemoryConversationMemory、ConversationMemoryController 的职责。
4. 为什么同步模型调用必须放在 boundedElastic，以及它与 SSE /stream 接口的关系。
5. Task 6 准备先新增或修改哪些测试和页面元素。

完成这些上下文恢复后，再继续手把手带我开发第三阶段剩余内容。
```

## 当前交接点

本文件生成时，第三阶段 Task 1～5 已完成，下一步为 Task 6 前端会话记忆面板。实际进度始终以 Git 历史、实施计划和当前代码为准。
